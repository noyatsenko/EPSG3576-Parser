import org.openqa.selenium.*;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Deprecated
public class ParseRussia {
    static FirefoxOptions firefoxOptions = new FirefoxOptions();
    static WebDriver webDriver;
    static long levelStartProcessingTime = 0;

    // размеры фрейма карты зависят от полушагов halfStep в index.html (128px)
    // и совпадают с размерами отображения фрейма во вьюверах (OpenLayers, Leaflet)
    // TODO: for 256 width for your Firefox min-width in browser/omni.ja to 120px, something like:
    // TODO: sed -i 's/min-width: 450px;/min-width: 120px;/g' /usr/lib/firefox-esr/browser/omni.ja
    final static int width = 512, height = 512;
    // задержка операций обращения к драйверу в миллисекундах, увеличить на слабых машинах
    final static int delay = 100; // имеем совесть парсить чужие сайты, поэтому увеличим минимальную задержку
    // адрес кастомной карты: требуется привязка к кнопкам действий и размеру шага
    final static String url = "http://localhost:8000";
    // компенсирует заголовок окна Firefox, который он по-умолчанию засчитывает в отображаемую область
    final static int firefoxTitleHeight = 85;
    // папка для сохранения тайлов
    static String tiles = "tiles/";
    // требуемые уровни зума рендеринга карты
    // TODO: do set map render level
    static int minLevel = 11, maxLevel = 11;

    // границы России в % от карты
    static double [] bboxLeftTopRightBottom = {0.34, 0.45, 0.65, 0.65};

    public static void main(String[] args) throws Exception{
        // инициализируем Firefox и загружаем карту
        webDriverInit();

        // запускаем рендеринг и снятие скриншотов, level карты аналогичен координате z и зуму zoom
        for (int level = minLevel; level <= maxLevel; level++) takeLevelTiles(level);

        // выключаем драйвер после окончания обработки
        webDriver.quit();
    }

    // проверяет, что данные фрейма загружены, минимальная задержка delay * 2
    public static void checkLoading() throws InterruptedException {
        do TimeUnit.MILLISECONDS.sleep(delay);
        while (webDriver.findElement(By.id("loadstatus")).isDisplayed());
        TimeUnit.MILLISECONDS.sleep(delay); // control run delay after loaded
    }

    // снимает скриншот уровня
    public static void takeLevelTiles(int level) throws Exception {
        // создаем папки на текущий уровень
        mkDirs(level);
        // увеличиваем карту от нулевого до заданного уровня
        zoom(level);
        // считаем полушаги от центра до края карты, количество которых заодно соответствует количеству колонок карты
        int halfSteps = countHalfSteps(level);

        // считаем тайлы России
        int [] tilebox = Arrays.stream(bboxLeftTopRightBottom).mapToInt(v -> (int) (v * halfSteps)).toArray();
        // нам потребуется перемещать курсор вниз нечетного столбца для алгоритма змейки, поэтому отрендерим и соседний
        if (tilebox[0] % 2 != 0) tilebox[0] -=1;
        // TODO: устанавливаем курсор в начало России, требуется bbox_ltrb < {0.5, 0.5,...}
        for (int x=0; x<(halfSteps - tilebox[0] * 2); x++) {moveLeft();}
        for (int y=0; y<(halfSteps - tilebox[1] * 2); y++) {moveUp();}
        // для России НЕ устанавливаем экран отображения карты (курсор текущего фрейма) в верхний левый угол
        // posLeftCorner(halfSteps);

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
    }

    // зуммирует карту от 0 на заданный левел
    public static void zoom(int level) throws InterruptedException {
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
    public static void resetCenter() throws InterruptedException {
        checkLoading();
        webDriver.findElement(By.id("resetcenter")).click();
    }

    // отправляет курсор в левый верхний угол, один раз за уровень
    public static void posLeftCorner(int halfSteps) throws Exception{
        for(int i = 0; i < halfSteps; i++){
            checkLoading();
            moveLeft();
            moveUp();
        }
    }

    // методы двигают курсор по карте на дистанцию полфрейма/полтайла/полэкрана
    public static void moveUp() throws InterruptedException {
        checkLoading();
        webDriver.findElement(By.id("moveup")).click();
    }

    public static void moveDown() throws InterruptedException {
        checkLoading();
        webDriver.findElement(By.id("movedown")).click();
    }

    public static void moveLeft() throws InterruptedException {
        checkLoading();
        webDriver.findElement(By.id("moveleft")).click();
    }

    public static void moveRight() throws InterruptedException {
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
//        for (int z = 0; z <= level; z++){
//            for (int x = 0; x <= countHalfSteps(z); x++){
//                new File("tiles/" + z + "/" + x + "/").mkdirs();
//            }
//        }
        for (int x = 0; x <= countHalfSteps(level); x++){
            new File("tiles/" + level + "/" + x + "/").mkdirs();
        }
    }

    // снимает скриншот карты под курсором (фрейм окна FireFox) и складывает в папку
    public static void takeScreen(int level, int x, int y) throws Exception{
        // избавляемся от артефактов отрисовки
        checkLoading();
        checkLoading();
        System.out.println("Saving: " + level + "/" + x + "/" + y + ".png");
        byte[] res = ((TakesScreenshot) webDriver).getScreenshotAs(OutputType.BYTES);
        FileOutputStream fos = new FileOutputStream(tiles + level +"/" + x + "/" + y + ".png");;
        fos.write(res);
        fos.close();
    }

    // выводит процент выполнения и оставшееся время
    public static void printStat(int x, int halfSteps){
        if (x == 0) levelStartProcessingTime = System.currentTimeMillis() / 1000L;
        else {
            // статистику мы рассчитываем за прошлый раунд, поэтому x уже инкрементирован
            halfSteps += 1;
            int readyPercent = 100 * x / (halfSteps);
            System.out.println("-- Ready " + readyPercent + "%");
            long levelThisProcessingTime = System.currentTimeMillis() / 1000L;
            long estimatedTime = (levelThisProcessingTime - levelStartProcessingTime) / x * (halfSteps - x);
            System.out.println("-- Estimated " + String.format("%02d:%02d:%02d", estimatedTime / 3600, estimatedTime / 60 % 60, estimatedTime % 60));
        }
    }
}
