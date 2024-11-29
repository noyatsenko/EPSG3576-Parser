import org.openqa.selenium.*;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

import java.io.File;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;

/**
 * Хитро парсит OSM сайт в квадратную полярную проекцию EPSG3576, используя Firefox со специальным локальным сайтом -- tile_renderer
 * Соответственно, надо иметь:
 * -- Firefox
 * -- Geckodriver для управления Firefox через Selenuim
 * -- запущенный tile_renderer на localhost:8000 (скрипт запуска - server.sh через питон в папке есть)
 * -- почитать static конфиги ниже, возможно выставить width\height 256 для не-MacOS применений (в index.html соответственно halfstep = 128)
 */
public class ParseRussiaSeq {
    static FirefoxOptions firefoxOptions = new FirefoxOptions();
    static WebDriver webDriver;
    static long levelStartProcessingTime = 0;

    // размеры фрейма карты зависят от полушагов halfStep в index.html (128px) (для рендера macOS в index.html устанавливать halfStep = 256)
    // и совпадают с размерами отображения фрейма во вьюверах (OpenLayers, Leaflet)
    // TODO: for 256 width for your Firefox min-width in browser/omni.ja to 120px, os-dependent, something like:
    // TODO: sed -i 's/min-width: 450px;/min-width: 120px;/g' /usr/lib/firefox-esr/browser/omni.ja
    final static int width = 512, height = 512; // размер фрейма карты: halfStep in px x2
    // задержка операций обращения к драйверу в миллисекундах, увеличить на слабых машинах
    final static int delay = 100; // имеем совесть парсить чужие сайты, поэтому увеличим минимальную задержку
    // адрес кастомной карты: требуется привязка к кнопкам действий и размеру шага
    final static String url = "http://localhost:8000";
    // компенсирует заголовок окна Firefox, который он по-умолчанию засчитывает в отображаемую область
    // TODO: настроить индивидуально под браузер и ОС (критерий: тайл квадратный и соответствует width/height)
    final static int firefoxTitleHeight = 85;
    // папка для сохранения тайлов
    static String tiles = "tiles/";

    // требуемые уровни зума рендеринга карты
    // TODO: установить требуемые уровни рендеринга карты
    static int minLevel = 11, maxLevel = 11;
    // максимальное количество тайлов которое разрешено рендерить в одном запуске фокса
    static int maxTiles = 50000;
    // максимальное количество столбцов которое разрешено рендерить в одном запуске фокса
    static int maxCols = 50;
    // минимальный столбец при старте алгоритма, использовать при аварийном перезапуске
    static int minColStart = 1022;

    // вся планета
    // static double [] bboxLeftTopRightBottom = {0.0, 0.0, 1.0, 1.0};
    // границы России в % от карты
    static double [] bboxLeftTopRightBottom = {0.34, 0.45, 0.65, 0.65};


    public static void main(String[] args){

        // запускаем рендеринг и снятие скриншотов, level карты аналогичен координате z и зуму zoom
        for (int level = minLevel; level <= maxLevel; level++) {
//            // создаем папки на текущий уровень
//            mkDirs(level);

            // считаем полушаги от центра до края карты, количество которых заодно соответствует количеству колонок карты
            int halfSteps = countHalfSteps(level);
            // считаем тайлы России
            int [] tilebox = Arrays.stream(bboxLeftTopRightBottom).mapToInt(v -> (int) (v * halfSteps)).toArray();
            // корректировка стартового столбца алгоритма
            if ( minColStart > tilebox[0] ) { tilebox[0] = minColStart; }
            // нам потребуется перемещать курсор вниз нечетного столбца для алгоритма змейки, поэтому отрендерим и соседний (левый)
            if (tilebox[0] % 2 != 0) tilebox[0] -=1;

            System.err.println("full tilebox: " + (Arrays.toString(tilebox)));
            if (minColStart > 0) System.err.println("Внимание! Установлен стартовый столбец minColStart: " + minColStart);

            // алгоритм, который перезапускает области рендеринга каждые maxCols столбцов чтобы не забивать кэши и не тормозить рендеринг
            // ====================================================================

            // пересчитаем maxCols так, чтобы не выходить за максимум тайлов: кэши и оперативка закончатся раньше 100 000, будет медленно
            int tmpCols = maxTiles / (tilebox[3] - tilebox[1]);
            if (maxCols > tmpCols) maxCols = tmpCols;
            if (maxCols % 2 != 0) maxCols =-1; // желательно четное количество столбцов

            int colSteps = (tilebox[2]-tilebox[0]) / maxCols + 1;
            System.err.println("col steps: " + colSteps );

            for (int i=0; i < colSteps; i++){
                // границы итерации
                int [] tileboxSeq = Arrays.copyOf(tilebox, tilebox.length);
                tileboxSeq[0] = tileboxSeq[0] + i*maxCols;
                tileboxSeq[2] = tileboxSeq[0] + maxCols;
                // проверки чтобы не рендерить лишнего
                if (tileboxSeq[2] %2 == 0) tileboxSeq[2]-=1;
                if (tileboxSeq[2] > tilebox[2]) tileboxSeq[2] = tilebox[2];

                // собственно чего мы тут все собрались:
                System.err.println("now tilebox: " + Arrays.toString(tileboxSeq));
                try { takeLevelTiles(level, tileboxSeq); }
                catch (Exception e) { throw new RuntimeException(e); }
                finally { webDriver.quit(); }
            }

            // ====================================================================
        }
    }

    // проверяет, что данные фрейма загружены, минимальная задержка delay * 2
    public static void checkLoading() throws InterruptedException {
        do TimeUnit.MILLISECONDS.sleep(delay);
        while (webDriver.findElement(By.id("loadstatus")).isDisplayed());
        TimeUnit.MILLISECONDS.sleep(delay); // control run delay after loaded
    }

    /**
     * снимает скриншот уровня в указанной области
     * @param level уровень
     * @param tilebox область рендеринга
     * @throws Exception
     */
    public static void takeLevelTiles(int level, int [] tilebox) throws Exception {
        // инициализируем Firefox и загружаем карту
        webDriverInit();

        // увеличиваем карту от нулевого до заданного уровня
        zoom(level);
        // считаем полушаги от центра до края карты, количество которых заодно соответствует количеству колонок карты
        int halfSteps = countHalfSteps(level);

        // нам потребуется перемещать курсор вниз нечетного столбца для алгоритма змейки, поэтому отрендерим и соседний (левый)
        if (tilebox[0] % 2 != 0) tilebox[0] -=1;
        // TODO: устанавливаем курсор в начало России
        // сдвиг происходит от центра карты: соответственно двигаем влево или вправо на начальную позицию
        int cshift = halfSteps - tilebox[0] * 2;
        if (cshift >=0) for (int x=0; x<cshift; x++) moveLeft(); // случай 0 не существует, но пусть будет для целостности: halfSteps всегда нечетный
        else for (int x=0; x<-cshift; x++) moveRight();
        // сдвиг происходит от центра карты: соответственно двигаем вверх или вниз на начальную позицию
        int vshift = halfSteps - tilebox[1] * 2;
        if (vshift >=0) for (int y=0; y<vshift; y++) moveUp();
        else for (int y=0; y<-vshift; y++) moveDown();
        // для начальных координат больше НЕ устанавливаем экран отображения карты (курсор текущего фрейма) в верхний левый угол
        // что конечно было удобно для расчетов и устраняло баги сдвигов, но очень уж медленно на секвентальных алгоритмах
//        posLeftCorner(halfSteps);

        // делаем обход карты курсором по алгоритму змейки
        // x по колонкам всегда движется в одном направлении: в сторону увеличения слева направо
        for (int x = tilebox[0]; x <= tilebox[2]; x++){
            printStat(x - tilebox[0], tilebox[2] - tilebox[0]);
            // а y движется вниз-вверх, в зависимости от четности колонки x
            if (x % 2 == 0){
                for (int y = tilebox[1]; y <= tilebox[3]; y++){
                    takeScreen(level, x, y);
                    // если этот шаг был не последний -- переходим в нижнюю строку по y за два полушага
                    if (y!=tilebox[3]) {moveDown(); moveDown();}
                }
            } else {
                for (int y = tilebox[3]; y >= tilebox[1]; y--){
                    takeScreen(level, x, y);
                    // если этот шаг был не последний -- переходим в верхнюю строку по y за два полушага
                    if (y!=tilebox[1]) {moveUp(); moveUp();}
                }
            }
            // переходим в следующую колонку по x за два полушага
            moveRight();
            moveRight();
        }

        // выключаем драйвер после окончания обработки
        webDriver.quit();
    }

    // зуммирует карту от 0 на заданный левел
    private static void zoom(int level) throws InterruptedException {
        // сбрасываем зум карты до исходного уровня методом перерисовки карты
        checkLoading();
        webDriver.findElement(By.id("resetzoom")).click();
        // масштабируем от 0 до level
        for (int i = 0; i < level; i++) {
            checkLoading();
            webDriver.findElement(By.className("ol-zoom-in")).click();
        }
        // webDriver.findElement(By.className("ol-zoom-out")).click(); // уменьшение зума, не используется
    }

    // возвращает в центр карты, используется для ручной отладки раскладки тайлов
    private static void resetCenter() throws InterruptedException {
        checkLoading();
        webDriver.findElement(By.id("resetcenter")).click();
    }

    // отправляет курсор в левый верхний угол, один раз за уровень
    private static void posLeftCorner(int halfSteps) throws Exception{
        for(int i = 0; i < halfSteps; i++){
            checkLoading();
            moveLeft();
            moveUp();
        }
    }

    // методы двигают курсор по карте на дистанцию полфрейма/полтайла/полэкрана
    private static void moveUp() throws InterruptedException {
        checkLoading();
        webDriver.findElement(By.id("moveup")).click();
    }

    private static void moveDown() throws InterruptedException {
        checkLoading();
        webDriver.findElement(By.id("movedown")).click();
    }

    private static void moveLeft() throws InterruptedException {
        checkLoading();
        webDriver.findElement(By.id("moveleft")).click();
    }

    private static void moveRight() throws InterruptedException {
        checkLoading();
        webDriver.findElement(By.id("moveright")).click();
    }
    // =========================================================

    // рассчитывает полушаги от центра до края карты, что также соответствует количеству столбцов\строк
    // rowsQuantity (количество столбцов) = countHalfSteps/2 (целые шаги от центра до края) *2 (влево от центра + вправо от центра)
    public static int countHalfSteps(int level){
        if (level == 0) return 0;
        if (level == 1) return 1;
        int halfSteps = 1;
        for (int i = 2; i <= level; i++){
            halfSteps = halfSteps * 2 + 1;
        }
        return halfSteps;
    }

    // инициализирует Firefox и скрывает элементы управления картой
    public static void webDriverInit() throws Exception{

        // переводим FF в фоновый режим, чтобы корректно выполнять рендеринг на любых мониторах
        // TODO: mandatory on production
        firefoxOptions.addArguments("--headless");

        webDriver = new FirefoxDriver(firefoxOptions);
        webDriver.manage().window().setSize(new Dimension(width, height+firefoxTitleHeight));
        webDriver.get(url);

        // ждем первоначальной загрузки и инициализации JS-кода карты после загрузки Firefox
        // webDriver блокирующий - но возвращает поток сразу после загрузки DOM, а не выполнения рендеринга JS
        TimeUnit.SECONDS.sleep(5);
        checkLoading();

        // скрываем элементы управления
        ((JavascriptExecutor) webDriver).executeScript("document.getElementsByClassName('ol-control')[0].style.opacity = '0';");
        ((JavascriptExecutor) webDriver).executeScript("document.getElementsByClassName('mover')[0].style.opacity = '0';");
    }

    // создает структуру папок на уровень карты
    public static void mkDirs(int level){
        for (int x = 0; x <= countHalfSteps(level); x++){
            new File("tiles/" + level + "/" + x + "/").mkdirs();
        }
    }

    // снимает скриншот карты под курсором (фрейм окна FireFox) и складывает в папку
    private static void takeScreen(int level, int x, int y) throws Exception{
        // избавляемся от артефактов отрисовки
        checkLoading();
        checkLoading();
        //System.out.println("Saving: " + level + "/" + x + "/" + y + ".png");
        byte[] res = ((TakesScreenshot) webDriver).getScreenshotAs(OutputType.BYTES);
        File f = new File(tiles + level + "/" + x + "/" + y + ".png");
        FileUtils.writeByteArrayToFile(f, res);
    }

    // выводит процент выполнения и оставшееся время
    public static void printStat(int x, int halfSteps){
        if (x == 0) levelStartProcessingTime = System.currentTimeMillis() / 1000L;
        else {
            // статистику мы рассчитываем за прошлый раунд, поэтому x уже инкрементирован
            halfSteps += 1;
            int readyPercent = 100 * x / (halfSteps);
            System.out.println("-- " + new Date() + " Ready " + readyPercent + "%");
            long levelThisProcessingTime = System.currentTimeMillis() / 1000L;
            long estimatedTime = (levelThisProcessingTime - levelStartProcessingTime) / x * (halfSteps - x);
            System.out.println("-- Estimated " + String.format("%02d:%02d:%02d", estimatedTime / 3600, estimatedTime / 60 % 60, estimatedTime % 60));
        }
    }
}