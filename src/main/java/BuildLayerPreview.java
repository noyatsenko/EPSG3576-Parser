import net.coobird.thumbnailator.Thumbnails;
import org.apache.commons.io.FileUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;

/**
 * Строит превью отрендеренной области сайта
 * Чтобы визуально проверить проблемы рендера, и возможно пропущенные из-за сбоев сети тайлы
 */
public class BuildLayerPreview {
    static String tiles = ParserRussiaSeq.tiles; // "tiles/"
    static String tiles_out = BuildOutTiles.tiles_out; // "tiles_out/"

    // TODO: целевое разрешение тайла на превью
    static int targetRes = 32;

    public static void main(String[] args) throws IOException {
        process(tiles);
        process(tiles_out);
    }

    private static void process(String dir) throws IOException {
        if ( ! new File(dir).exists()) {
            System.err.println("Папка не существует: " + dir);
            return;
        }

        File[] layers = new File(dir).listFiles(File::isDirectory);
        for (File layer : layers){
            // считаем количество столбцов и строк которые у нас фактически есть
            int colSize = layer.listFiles(File::isDirectory).length;
            int rowSize = layer.listFiles(File::isDirectory)[0].listFiles(File::isFile).length;

            System.err.println("Ожидаемое количество тайлов: " + colSize*rowSize);

            // холст итогового изображения на котором тайлы отрисуются (превью)
            // может понадобиться уменьшить targetRes если холст не поместится в 2^32 бита по одной из сторон
            BufferedImage canvas = new BufferedImage(colSize* targetRes, rowSize* targetRes, BufferedImage.TYPE_INT_ARGB);
            Comparator<File> numericComparator = Comparator.comparing(f -> Integer.parseInt(f.getName().replace(".png","")));

            // так поменьше переписывать логики сервиса, просто убрав конкретную область расчетов в отдельный относительно последовательный поток
            // читать невозможно, зато писать удобно, воткнув отправку в поток и ожидание
            // и этот метод относительно последовательно обращается к памяти по строкам, обеспечивая по возможности максимум производительности
            ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
            File [] cols = layer.listFiles(File::isDirectory);
            Arrays.sort(cols, numericComparator);
            for (int col = 0; col<cols.length; col++){
                List<File> rows = FileUtils.listFiles(cols[col], new String[]{"png"},false).stream().sorted(numericComparator).toList();
                for (int row = 0; row < rows.size(); row++){
                    final int finalCol = col;
                    final int finalRow = row;
                    executor.execute(() -> {
                        try {
                            // ресайзим тайл в targetRes
                            BufferedImage png = Thumbnails.of(rows.get(finalRow)).size(targetRes, targetRes).asBufferedImage();
                            // рисуем на холсте по координатам
                            canvas.getGraphics().drawImage(png, finalCol * targetRes, finalRow * targetRes, null);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
            }
            // этот блок традиционно ждет, когда пул завершит работу
            executor.shutdown();
            try { executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS); }
            catch (Exception ignored) {}
            ImageIO.write(canvas,"png",new File(dir, layer.getName()+".png"));
        }
    }
}
