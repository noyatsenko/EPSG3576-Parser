import net.coobird.thumbnailator.Thumbnailator;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ресайзит png полученные из парсера в размер для сайта
 * Точно нужен для MacOS, потому что мак с retina рендерит x2
 */
public class BuildOutTiles {
    static String tiles = ParseRussiaSeq.tiles; // "tiles/"
    static String tiles_out = "tiles_out/";
    static String tile_ext = "png";
    // TODO: целевое разрешение тайла для сайта (обычно 256)
    static int tileRes = 256;

    public static void main(String[] args) throws IOException {
        Collection<File> pngs = FileUtils.listFiles(new File(ParseRussiaSeq.tiles), new String[]{tile_ext}, true);

        // многопоточный конвертер через Thumbnailator
        AtomicBoolean res = new AtomicBoolean(true);
        pngs.stream().parallel().forEach(png -> {
            try {
                File out = new File(png.getAbsolutePath().replace(tiles, tiles_out));
                FileUtils.touch(out);
                Thumbnailator.createThumbnail(png, out, tileRes, tileRes);
            } catch (Exception ex) { res.set(false); }
        });
        System.err.println("Результат сжатия: " + res);
    }
}
