package net.immortalmc.bluemap_sign_markers.helpers;

import java.io.*;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

import static net.immortalmc.bluemap_sign_markers.Constants.IMAGE_PATH;
import static net.immortalmc.bluemap_sign_markers.Constants.RES_FOLDER;

/**
 * Small utility that copies marker icons bundled inside the plugin JAR into the BlueMap webroot
 * so BlueMap can serve them as marker icons.
 */
public class IconHelper {


    /**
     * Copies PNG, SVG and WebP marker resources from the plugin JAR (inside {@code RES_FOLDER})
     * into the target BlueMap webroot under {@code IMAGE_PATH}. Sub-directories are preserved, so
     * bundled sets land beside the user's own ({@code web/markers/set1/...}). Existing files are
     * not overwritten.
     *
     * @param jar the plugin JAR file to read resources from
     * @param webRoot the BlueMap webroot path to copy files into
     * @param logger logger used to report warnings and errors
     * @return true when copy operation completed (skipping existing files), false on failure to create directories
     * @throws IOException when reading from the JAR or writing files fails
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static boolean copyMarkers(JarFile jar, Path webRoot, Logger logger) throws IOException {

        File targetRoot = webRoot.resolve(IMAGE_PATH).toFile();
        if (!targetRoot.exists() && !targetRoot.mkdirs()) {
            logger.warning("Cannot create folder: " + targetRoot.getAbsolutePath());
            return false;
        }

        Path resourceRoot = Path.of(RES_FOLDER);
        Enumeration<JarEntry> entries = jar.entries();

        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String entryName = entry.getName();

            if (!entry.isDirectory()
                    && entryName.startsWith(RES_FOLDER + "/")
                    && IconResolver.isSupportedIconFile(entryName)) {

                Path entryPath = Path.of(entryName);
                Path relative = resourceRoot.relativize(entryPath);
                File outFile = webRoot.resolve(IMAGE_PATH).resolve(relative).toFile();

                if (outFile.exists()) {
                    continue;
                }

                if (!outFile.getParentFile().exists()) {
                    outFile.getParentFile().mkdirs();
                }

                try (InputStream in = jar.getInputStream(entry)) {
                    if (in == null) {
                        logger.warning("❌ Cannot read file from JAR: " + entryName);
                        continue;
                    }

                    try (OutputStream out = new FileOutputStream(outFile)) {
                        in.transferTo(out);
                    }
                }
            }
        }
        return true;
    }

}
