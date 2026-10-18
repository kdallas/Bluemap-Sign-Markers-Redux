package net.immortalmc.bluemap_sign_markers.helpers;

import com.flowpowered.math.vector.Vector2i;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.immortalmc.bluemap_sign_markers.Constants.IMAGE_PATH;

/**
 * Resolves the icon token a player writes on a sign's first line (e.g. {@code [portal]}) to an
 * icon BlueMap can serve, together with the anchor BlueMap places the marker with.
 *
 * <p>Resolution order:
 * <ol>
 *   <li><b>Format</b>: {@code .svg}, then {@code .webp}, then {@code .png}. This outranks the
 *       directory: an SVG in a set folder beats a PNG in the root, because the formats are
 *       alternatives for the same icon rather than different icons;</li>
 *   <li><b>Directory</b>, within one format: the {@code markers/} root first, then each direct
 *       sub-directory in case-insensitive natural order ({@code set1/}, {@code set2/}, ...,
 *       {@code set10/} after {@code set9/}).</li>
 * </ol>
 * A token carrying an extension is also tried verbatim before any of that, so
 * {@code [portal.svg]} names the file {@code portal.svg} and never probes {@code portal.svg.png}.
 * The first candidate that is a regular file wins. When nothing matches, the bundled
 * {@code ban_white} icon is found the same way.
 *
 * <p>A token may name one sub-directory explicitly, as {@code [set2/tree]}. That searches only
 * the named folder, skipping the normal order; if the folder or file is missing, the fallback is
 * used rather than silently trying elsewhere.
 *
 * <p>The token is player input and is treated as such: it must be a plain file name inside
 * {@code markers/} or one of its sub-directories, with at most one path separator, no
 * URL-significant characters and no control characters. Anything else resolves to the fallback.
 *
 * <p>{@link #resolve} never returns {@code null} and never propagates an I/O failure. An icon
 * whose size cannot be determined is still used, anchored at {@link #DEFAULT_ANCHOR}, with a
 * warning — losing the icon would lose the marker.
 */
public final class IconResolver {

    /**
     * Icon used when the requested one cannot be resolved. Ships with the plugin.
     * <p>
     * Public so callers that report the result to a player can recognise the fallback without
     * repeating the name.
     */
    public static final String FALLBACK_NAME = "ban_white";

    private static final String SVG_EXTENSION = ".svg";
    private static final String WEBP_EXTENSION = ".webp";
    private static final String PNG_EXTENSION = ".png";

    /**
     * Size assumed for an icon whose real size cannot be determined, in pixels.
     * <p>
     * Fifty matches the SVG icons already in use: the markers imported into the BlueMap config
     * from Dynmap carry anchors of (25, 25), i.e. the centre of a 50x50 icon.
     * <p>
     * It is also the <b>ceiling</b> for viewBox-derived sizing, and only there: a viewBox is
     * routinely far larger than an icon should render, so it is scaled <i>down</i> to this size.
     * A dimension taken from a {@code width}/{@code height} attribute is never capped.
     */
    public static final int DEFAULT_ICON_SIZE = 50;

    /**
     * Anchor used when an icon's size cannot be determined: the centre of a
     * {@link #DEFAULT_ICON_SIZE} square. Derived rather than hardcoded so the two cannot drift.
     * <p>
     * Never worse than BlueMap's default of (0, 0), which pins the icon's top-left corner to the
     * marker and makes it hang down-right. {@link Vector2i} is immutable, so sharing one instance
     * across markers is safe.
     */
    public static final Vector2i DEFAULT_ANCHOR =
            new Vector2i(DEFAULT_ICON_SIZE / 2, DEFAULT_ICON_SIZE / 2);

    /** Longest token accepted. A sign line can hold far more than any real icon name. */
    private static final int MAX_TOKEN_LENGTH = 128;

    /** Bytes of an SVG inspected for the root element. The root tag is at the very start. */
    private static final int MAX_SVG_HEAD_BYTES = 64 * 1024;

    /** Sanity cap on a parsed dimension, so a nonsense value cannot produce a nonsense anchor. */
    private static final double MAX_ICON_DIMENSION = 100_000d;

    /** SVG number, per the SVG/CSS grammar: sign, digits with optional fraction, optional exponent. */
    private static final String NUMBER = "[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?";

    /** CSS length with an optional absolute unit. Percentages and relative units do not match. */
    private static final Pattern LENGTH = Pattern.compile(
            "^\\s*(" + NUMBER + ")\\s*(px|pt|pc|mm|cm|in)?\\s*$", Pattern.CASE_INSENSITIVE);

    /** viewBox: exactly four numbers, separated by whitespace, a comma, or nothing before a sign. */
    private static final Pattern VIEW_BOX = Pattern.compile(
            "^\\s*(" + NUMBER + ")\\s*,?\\s*(" + NUMBER + ")\\s*,?\\s*(" + NUMBER
                    + ")\\s*,?\\s*(" + NUMBER + ")\\s*$");

    /**
     * One attribute of a start tag: a name (optionally namespace-prefixed) and a quoted value.
     * Unquoted values are not valid XML and are not matched: a browser would reject such a
     * document too, so there is nothing to size.
     */
    private static final Pattern ATTRIBUTE = Pattern.compile(
            "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    private IconResolver() {
    }

    /**
     * An icon BlueMap can serve for a sign.
     *
     * @param path   webroot-relative path as stored in the marker, e.g. {@code markers/portal.svg}
     *               (always forward slashes, since it ends up in a URL)
     * @param name   player-facing name used in the chat feedback, e.g. {@code portal} or
     *               {@code portal.svg}, and {@code ban_white} when the fallback was used
     * @param anchor pixel within the icon that sits on the marker position
     */
    public record IconRef(String path, String name, Vector2i anchor) {
        public IconRef {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(anchor, "anchor");
        }
    }

    /**
     * Resolves a sign's icon token to an icon and its anchor.
     *
     * @param webRoot BlueMap webroot, or {@code null} when BlueMap has not initialised yet
     * @param token   text between the brackets on the sign's first line, already lower-cased by
     *                the caller; surrounding whitespace is trimmed here
     * @param logger  plugin logger, used for warnings
     * @return the resolved icon, or the fallback icon when the token is unusable or names no
     *         existing file. Never {@code null}.
     */
    public static IconRef resolve(Path webRoot, String token, Logger logger) {
        Objects.requireNonNull(logger, "logger");

        if (webRoot == null) {
            // getWebRoot() is null until BlueMap initialises, and BlueMap can run without the
            // web-app at all. Nothing can be verified, or fetched by a browser, in that state.
            logger.warning("Cannot resolve marker icon '" + printable(token)
                    + "': BlueMap web-root is not available; using '" + FALLBACK_NAME + "'");
            return fallback(null, logger);
        }

        Path iconDir = webRoot.resolve(IMAGE_PATH);
        String safeToken = sanitize(token, logger);

        if (safeToken != null) {
            IconRef found = find(iconDir, safeToken, logger);
            if (found != null) return found;
            logger.fine("No icon file for sign token '" + safeToken + "' in " + iconDir
                    + " or its sub-directories; using '" + FALLBACK_NAME + "'");
        }

        return fallback(iconDir, logger);
    }

    /**
     * Whether a file name has an extension this plugin can make sense of. Shared with
     * {@link IconHelper} so the supported set is defined once.
     *
     * @param fileName a plain file name, e.g. {@code portal.svg}
     * @return true for {@code .png}, {@code .svg} and {@code .webp}, case-insensitively
     */
    public static boolean isSupportedIconFile(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(SVG_EXTENSION)
                || lower.endsWith(WEBP_EXTENSION)
                || lower.endsWith(PNG_EXTENSION);
    }

    // ------------------------------------------------------------------
    // Resolution
    // ------------------------------------------------------------------

    /**
     * Validates a player-supplied token and trims it.
     *
     * @return the usable token, or {@code null} when it must be rejected
     */
    private static String sanitize(String token, Logger logger) {
        if (token == null) return null;
        String trimmed = token.trim();
        if (trimmed.isEmpty()) return null;

        if (trimmed.length() > MAX_TOKEN_LENGTH) {
            logger.warning("Ignoring sign icon name '" + printable(trimmed) + "': longer than "
                    + MAX_TOKEN_LENGTH + " characters");
            return null;
        }

        if (!isValidToken(trimmed)) {
            logger.warning("Ignoring sign icon name '" + printable(trimmed)
                    + "': use a file name, optionally in one sub-directory (for example set2/tree)");
            return null;
        }

        return trimmed;
    }

    /**
     * Whether the token is a plain file name, or one sub-directory prefix and a file name.
     * <p>
     * The slash is player input too: only one is allowed (the search is one level deep anyway),
     * and each half is validated as a name on its own.
     */
    private static boolean isValidToken(String token) {
        int slash = token.indexOf('/');
        if (slash < 0) return isSafeName(token);

        return slash > 0
                && slash < token.length() - 1
                && token.indexOf('/', slash + 1) < 0
                && isSafeName(token.substring(0, slash))
                && isSafeName(token.substring(slash + 1));
    }

    private static boolean isSafeName(String name) {
        return !name.isEmpty()
                && !name.equals(".")
                && !name.equals("..")
                && !containsUnsafeCharacter(name);
    }

    /**
     * Characters that must not appear inside one name segment. A single slash is allowed in the
     * token as the folder separator, but a slash inside a segment would add another level.
     * <p>
     * A backslash is a separator on Windows; ":" allows a drive-relative path ("C:secret") and
     * an NTFS alternate data stream; "%", "?" and "#" are significant to the browser that
     * fetches the icon URL; control characters only arrive from a client that is not typing and
     * would end up in the log message.
     */
    private static boolean containsUnsafeCharacter(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '%' || c == '?' || c == '#'
                    || Character.isISOControl(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * File names to probe, in priority order.
     * <p>
     * A token carrying an extension is a file name the player spelled out, so it is probed
     * verbatim first. The extension probes still follow it so that an icon whose own name
     * contains a dot ({@code [my.icon]} for {@code my.icon.png}) keeps working as it did before
     * SVG support existed; they only ever run after the verbatim probe has missed. The order is
     * the format priority: SVG, then WebP, then PNG.
     */
    private static List<String> candidates(String token) {
        List<String> fileNames = new ArrayList<>(4);
        if (token.indexOf('.', 1) >= 0) fileNames.add(token);
        fileNames.add(token + SVG_EXTENSION);
        fileNames.add(token + WEBP_EXTENSION);
        fileNames.add(token + PNG_EXTENSION);
        return fileNames;
    }

    /**
     * First icon matching {@code token}, which may carry an explicit {@code folder/} prefix.
     * <p>
     * Without a prefix, formats are the outer loop and directories the inner one: every directory
     * is asked for {@code <name>.svg} before any is asked for {@code <name>.webp}, and so on.
     * Directories themselves are tried root first, then sorted by name. With a prefix only that
     * one folder is searched.
     *
     * @return the match, or {@code null} when no candidate is a regular file
     */
    private static IconRef find(Path iconDir, String token, Logger logger) {
        int slash = token.indexOf('/');
        String folder = slash < 0 ? null : token.substring(0, slash);
        String name = slash < 0 ? token : token.substring(slash + 1);

        List<Path> dirs;
        if (folder == null) {
            dirs = searchDirectories(iconDir, logger);
        } else {
            Path dir = iconDir.resolve(folder);
            // sanitize() guarantees a plain one-level folder name, so this only fails for an
            // unknown folder or a non-directory. An explicit folder means exactly that folder:
            // a miss falls back rather than quietly resolving the same name elsewhere.
            if (!isDirectChild(iconDir, dir) || !Files.isDirectory(dir)) return null;
            dirs = List.of(dir);
        }

        for (String fileName : candidates(name)) {
            for (Path dir : dirs) {
                Path file = dir.resolve(fileName);
                // Defence in depth: each half is a plain name, so this can only fail if that
                // check is ever loosened. It costs one normalization.
                if (!isDirectChild(dir, file) || !Files.isRegularFile(file)) continue;

                String relative = dir.equals(iconDir)
                        ? fileName
                        : dir.getFileName() + "/" + fileName;
                return new IconRef(IMAGE_PATH + relative, token, anchorFor(file, fileName, logger));
            }
        }
        return null;
    }

    /**
     * The directories to search, in priority order: the icon root, then its direct
     * sub-directories sorted by name.
     * <p>
     * Only one level is searched. A sub-directory exists to group a set of icons, not to build a
     * tree, and a bounded search keeps a stray folder full of images from slowing every sign
     * down.
     */
    private static List<Path> searchDirectories(Path iconDir, Logger logger) {
        List<Path> dirs = new ArrayList<>();
        if (!Files.isDirectory(iconDir)) return dirs;

        dirs.add(iconDir);

        List<Path> subDirs = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(iconDir, Files::isDirectory)) {
            children.forEach(subDirs::add);
        } catch (IOException e) {
            // The root is still usable, so return it rather than losing every icon over a
            // listing failure.
            logger.warning("Cannot list icon sub-directories in " + iconDir + ": " + e.getMessage());
            return dirs;
        }

        subDirs.sort(Comparator.comparing((Path dir) -> dir.getFileName().toString(), DIRECTORY_ORDER));
        dirs.addAll(subDirs);
        return dirs;
    }

    /**
     * Directory names in the order a person reads them: case-insensitively, with runs of digits
     * compared by value. A plain lexicographic sort would put {@code set10} before {@code set2},
     * which is almost never what a numbered set means.
     */
    private static final Comparator<String> DIRECTORY_ORDER = IconResolver::compareNatural;

    private static int compareNatural(String a, String b) {
        int i = 0;
        int j = 0;

        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);

            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int endA = endOfDigits(a, i);
                int endB = endOfDigits(b, j);
                String digitsA = stripLeadingZeros(a.substring(i, endA));
                String digitsB = stripLeadingZeros(b.substring(j, endB));

                // Equal-length digit strings compare lexicographically exactly as their values
                // do; the length difference is what makes 9 < 10.
                if (digitsA.length() != digitsB.length()) {
                    return Integer.compare(digitsA.length(), digitsB.length());
                }
                int cmp = digitsA.compareTo(digitsB);
                if (cmp != 0) return cmp;

                i = endA;
                j = endB;
                continue;
            }

            int cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
            if (cmp != 0) return cmp;
            i++;
            j++;
        }

        int cmp = Integer.compare(a.length(), b.length());
        return cmp != 0 ? cmp : a.compareTo(b);
    }

    private static int endOfDigits(String s, int start) {
        int end = start;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        return end;
    }

    private static String stripLeadingZeros(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') start++;
        return digits.substring(start);
    }

    private static boolean isDirectChild(Path dir, Path file) {
        Path parent = file.normalize().getParent();
        return parent != null && parent.equals(dir.normalize());
    }

    private static IconRef fallback(Path iconDir, Logger logger) {
        if (iconDir != null) {
            IconRef found = find(iconDir, FALLBACK_NAME, logger);
            if (found != null) return found;

            // IconHelper.copyMarkers copies it at startup, so this means the webroot was
            // edited or the copy failed. The marker is still created: the reference is
            // correct and the file may reappear.
            logger.warning("Fallback icon '" + FALLBACK_NAME + "' is missing from " + iconDir
                    + " and its sub-directories; markers will show a broken image until it is restored");
        }

        return new IconRef(IMAGE_PATH + FALLBACK_NAME + PNG_EXTENSION, FALLBACK_NAME, DEFAULT_ANCHOR);
    }

    /**
     * Anchor for an existing file: half its size, or {@link #DEFAULT_ANCHOR} when the size
     * cannot be determined.
     */
    private static Vector2i anchorFor(Path file, String fileName, Logger logger) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        int[] size;
        if (lower.endsWith(SVG_EXTENSION)) {
            size = svgSize(file);
        } else if (lower.endsWith(WEBP_EXTENSION)) {
            size = webpSize(file);
        } else {
            size = rasterSize(file);
        }

        if (size == null) {
            // An icon that exists is used even when it cannot be measured. A wrong anchor moves
            // the icon; refusing the icon would lose the marker.
            logger.warning("Cannot determine the size of icon " + file + "; anchoring it at "
                    + DEFAULT_ANCHOR + ". Give an SVG an explicit width and height, or use a PNG "
                    + "or WebP, for a centred marker");
            return DEFAULT_ANCHOR;
        }

        return new Vector2i(size[0] / 2, size[1] / 2);
    }

    // ------------------------------------------------------------------
    // Sizing: raster formats
    // ------------------------------------------------------------------

    /**
     * Dimensions of a raster image, read from its header.
     * <p>
     * {@code ImageIO.read(File)} would decode the whole image (a 20000x20000 PNG is ~1.6 GB of
     * heap) and returns {@code null} — not an exception — for anything it cannot decode. Asking a
     * reader for the header has neither problem; an unreadable or unknown format simply yields no
     * reader.
     *
     * @return {width, height}, or {@code null} when the format cannot be read
     */
    private static int[] rasterSize(Path file) {
        // A File argument gives a FileImageInputStream; any other input type would go through
        // FileCacheImageInputStream, which writes a temporary copy of the image to disk.
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) return null;

            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return isValidSize(width, height) ? new int[] { width, height } : null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            // A truncated or corrupt header can surface as either; both mean "no size known".
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Sizing: WebP
    // ------------------------------------------------------------------

    /**
     * Dimensions of a WebP, read from its RIFF chunk headers without decoding the image.
     * <p>
     * The JDK ships no WebP {@link ImageReader}, so {@link #rasterSize} cannot measure one and
     * every WebP icon would otherwise be anchored at {@link #DEFAULT_ANCHOR}. The size is stated
     * up front in one of three chunk types: {@code VP8X} (extended), {@code VP8L} (lossless) or
     * {@code VP8 } (lossy). Only that chunk is read; unknown chunks before it ({@code ICCP},
     * {@code EXIF}, {@code ALPH}) are skipped by length, so this remains a header-only check.
     *
     * @return {width, height}, or {@code null} when the file is not a WebP or its header is
     *         truncated or malformed
     */
    private static int[] webpSize(Path file) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] riff = in.readNBytes(12);
            if (riff.length < 12 || !matchesAscii(riff, 0, "RIFF") || !matchesAscii(riff, 8, "WEBP")) {
                return null;
            }

            while (true) {
                byte[] header = in.readNBytes(8);
                if (header.length < 8) return null;

                long chunkSize = u32(header, 4);

                if (matchesAscii(header, 0, "VP8X")) {
                    if (chunkSize < 10) return null;
                    byte[] payload = in.readNBytes(10);
                    if (payload.length < 10) return null;
                    // Canvas size, stored minus one, 24 bits little-endian.
                    return sizeOrNull(u24(payload, 4) + 1, u24(payload, 7) + 1);
                }

                if (matchesAscii(header, 0, "VP8L")) {
                    if (chunkSize < 5) return null;
                    byte[] payload = in.readNBytes(5);
                    if (payload.length < 5 || (payload[0] & 0xFF) != 0x2F) return null;
                    // 14 bits of width-1, then 14 bits of height-1, packed little-endian.
                    long bits = u32(payload, 1);
                    return sizeOrNull((int) (bits & 0x3FFF) + 1,
                            (int) ((bits >>> 14) & 0x3FFF) + 1);
                }

                if (matchesAscii(header, 0, "VP8 ")) {
                    if (chunkSize < 10) return null;
                    byte[] payload = in.readNBytes(10);
                    if (payload.length < 10
                            || (payload[3] & 0xFF) != 0x9D
                            || (payload[4] & 0xFF) != 0x01
                            || (payload[5] & 0xFF) != 0x2A) {
                        return null;
                    }
                    // Frame tag, then the 3-byte start code, then 14-bit dimensions.
                    return sizeOrNull(u16(payload, 6) & 0x3FFF, u16(payload, 8) & 0x3FFF);
                }

                // RIFF chunks are padded to an even length. skipNBytes makes full progress or
                // throws, so a truncated or hostile file cannot spin this loop.
                in.skipNBytes(chunkSize + (chunkSize & 1L));
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean matchesAscii(byte[] data, int offset, String ascii) {
        if (offset + ascii.length() > data.length) return false;
        for (int i = 0; i < ascii.length(); i++) {
            if ((data[offset + i] & 0xFF) != ascii.charAt(i)) return false;
        }
        return true;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int u24(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16);
    }

    private static long u32(byte[] data, int offset) {
        return (data[offset] & 0xFFL)
                | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16)
                | ((data[offset + 3] & 0xFFL) << 24);
    }

    private static int[] sizeOrNull(int width, int height) {
        return isValidSize(width, height) ? new int[] { width, height } : null;
    }

    // ------------------------------------------------------------------
    // Sizing: SVG
    // ------------------------------------------------------------------

    /**
     * Dimensions of an SVG, in the order the rules require.
     * <ol>
     *   <li>Both {@code width} and {@code height} usable — used exactly as supplied, never capped.
     *       That is what a browser draws, so the anchor matches the rendered icon.</li>
     *   <li>Exactly one usable — taken as supplied and never capped, with the other dimension
     *       derived from the viewBox ratio, as a browser does. With no viewBox there is no ratio,
     *       so the icon is assumed square.</li>
     *   <li>Neither usable, viewBox present — the viewBox is scaled down so its longest edge is
     *       {@link #DEFAULT_ICON_SIZE}, preserving the ratio. ViewBox numbers are routinely far
     *       larger than an icon renders, so they must not be taken as pixels.</li>
     *   <li>Nothing usable — {@code null}, and the caller falls back to {@link #DEFAULT_ANCHOR}.</li>
     * </ol>
     *
     * @return {width, height} in CSS pixels, or {@code null} when nothing usable was found
     */
    private static int[] svgSize(Path file) {
        String head = readHead(file);
        if (head == null) return null;

        String rootTag = rootElement(head);
        if (rootTag == null) return null;

        Map<String, String> attributes = attributes(rootTag);
        Double width = lengthInPx(attributes.get("width"));
        Double height = lengthInPx(attributes.get("height"));
        double[] box = viewBox(attributes.get("viewbox"));

        // Rule 1: both supplied.
        if (width != null && height != null) return toSize(width, height);

        // Rule 2: one supplied. The given dimension is authoritative; only the missing one is
        // derived, and only when a ratio exists.
        if (width != null) {
            return toSize(width, box != null ? width * box[3] / box[2] : width);
        }
        if (height != null) {
            return toSize(box != null ? height * box[2] / box[3] : height, height);
        }

        // Rule 3: neither supplied - the viewBox is the only hint, and it must be scaled.
        if (box != null) return scaledIntoIconSize(box[2], box[3]);

        // Rule 4.
        return null;
    }

    /**
     * Scales a pair of dimensions so the longest edge is at most {@link #DEFAULT_ICON_SIZE},
     * preserving the ratio. Dimensions already within that size are returned unchanged: the
     * default is a ceiling, not a target.
     */
    private static int[] scaledIntoIconSize(double width, double height) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0) {
            return null;
        }

        double longest = Math.max(width, height);
        if (longest > DEFAULT_ICON_SIZE) {
            double factor = DEFAULT_ICON_SIZE / longest;
            return toSize(width * factor, height * factor);
        }
        return toSize(width, height);
    }

    /**
     * Head of the file, decoded as UTF-8, with a leading BOM removed.
     * <p>
     * The byte cap also bounds the work: an oversized or hostile file is never read beyond it,
     * and a root tag that does not fit is simply not found. Decoding cannot corrupt the structure
     * being scanned either — UTF-8 continuation bytes are all &gt;= 0x80 and never decode to an
     * ASCII delimiter such as {@code <}, {@code >} or {@code "}.
     */
    private static String readHead(Path file) {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(file)) {
            bytes = in.readNBytes(MAX_SVG_HEAD_BYTES);
        } catch (IOException e) {
            return null;
        }

        String text = new String(bytes, StandardCharsets.UTF_8);
        // U+FEFF is not whitespace, so a BOM would look like junk before the root element and
        // defeat the scan on files written by Windows tools.
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return text;
    }

    /**
     * The root element's start tag, or {@code null} when the document does not open with an
     * {@code svg} element.
     * <p>
     * Skips what XML allows before the root element (whitespace, the XML declaration, processing
     * instructions, comments, a DOCTYPE) rather than searching for the first {@code "<svg"}
     * anywhere, which a mention inside a comment would fool. Lenient in one direction only: a
     * document that is not well-formed enough to have an {@code svg} root is one a browser would
     * not render either.
     */
    private static String rootElement(String text) {
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);

            if (c == '<') {
                if (text.startsWith("<!--", i)) {
                    int end = text.indexOf("-->", i + 4);
                    if (end < 0) return null;
                    i = end + 3;
                    continue;
                }
                if (text.startsWith("<?", i)) {
                    int end = text.indexOf("?>", i + 2);
                    if (end < 0) return null;
                    i = end + 2;
                    continue;
                }
                if (text.startsWith("<!", i)) {                 // DOCTYPE, including internal subset
                    int end = text.indexOf('>', i + 2);
                    if (end < 0) return null;
                    i = end + 1;
                    continue;
                }

                int end = tagEnd(text, i);
                if (end < 0) return null;
                String tag = text.substring(i, end + 1);
                return isSvgElement(tag) ? tag : null;
            }

            if (!Character.isWhitespace(c)) return null;        // junk before the root element
            i++;
        }
        return null;
    }

    /**
     * Index of the {@code '>'} that closes the tag opened at {@code start}, or -1 when the tag is
     * unterminated or malformed. Quoted attribute values are skipped, so a {@code '>'} inside one
     * does not end the tag early; a self-closing {@code "<svg/>"} is an ordinary tag here.
     */
    private static int tagEnd(String text, int start) {
        char quote = 0;
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return i;
            } else if (c == '<') {
                return -1;
            }
        }
        return -1;
    }

    /**
     * Whether a start tag names the SVG root element. Compared by local name, so a document that
     * declares a namespace prefix ({@code <svg:svg ...>}) is recognised. Case-sensitive, because
     * XML is: a browser parsing an {@code image/svg+xml} file treats {@code <SVG>} as an unknown
     * element and renders nothing, so there would be no size worth reporting.
     */
    private static boolean isSvgElement(String tag) {
        int end = 1;
        while (end < tag.length()) {
            char c = tag.charAt(end);
            if (c == '>' || c == '/' || Character.isWhitespace(c)) break;
            end++;
        }
        return "svg".equals(localName(tag.substring(1, end)));
    }

    /**
     * Attributes of a start tag, keyed by lower-cased local name, so {@code viewBox},
     * {@code svg:viewBox} and {@code VIEWBOX} all resolve to the same key. First occurrence of a
     * name wins.
     */
    private static Map<String, String> attributes(String tag) {
        Map<String, String> attributes = new HashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(tag);
        while (matcher.find()) {
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            attributes.putIfAbsent(localName(matcher.group(1)).toLowerCase(Locale.ROOT), value);
        }
        return attributes;
    }

    private static String localName(String qualifiedName) {
        int colon = qualifiedName.lastIndexOf(':');
        return colon < 0 ? qualifiedName : qualifiedName.substring(colon + 1);
    }

    /**
     * An SVG length attribute in CSS pixels.
     * <p>
     * Accepted: a plain number (user units, which are CSS pixels) and the absolute units
     * {@code px}, {@code in}, {@code cm}, {@code mm}, {@code pt}, {@code pc}, converted with the
     * CSS factors (96 px per inch). Rejected, all of which mean "not determinable from the file":
     * percentages ({@code width="100%"} depends on the container), the relative units {@code em},
     * {@code ex}, {@code rem}, {@code ch}, {@code vw}, {@code vh}, {@code auto}, an empty value, a
     * missing attribute, an unquoted value, and zero or negative numbers (SVG treats those as an
     * error and renderers ignore the attribute, so treating it as unknown matches what is drawn).
     * <p>
     * CSS ({@code style="width:32px"} and {@code <style>} rules) is not read: an SVG's intrinsic
     * size comes from these attributes, which is what a browser uses to lay the image out.
     *
     * @return the length in pixels, or {@code null} when it cannot be determined
     */
    private static Double lengthInPx(String value) {
        if (value == null) return null;

        Matcher matcher = LENGTH.matcher(value);
        if (!matcher.matches()) return null;

        double number = Double.parseDouble(matcher.group(1));
        if (!Double.isFinite(number) || number <= 0) return null;

        double pixels = number * unitFactor(matcher.group(2));
        return Double.isFinite(pixels) && pixels > 0 ? pixels : null;
    }

    private static double unitFactor(String unit) {
        if (unit == null) return 1d;                            // unitless
        switch (unit.toLowerCase(Locale.ROOT)) {
            case "in": return 96d;
            case "cm": return 96d / 2.54d;
            case "mm": return 96d / 25.4d;
            case "pt": return 96d / 72d;
            case "pc": return 16d;
            default:   return 1d;                               // px
        }
    }

    /**
     * A {@code viewBox} attribute as {x, y, width, height}.
     * <p>
     * Accepted: exactly four numbers, separated by whitespace, a comma, or nothing before a signed
     * number, with a positive width and height. Rejected: fewer or more than four numbers, anything
     * non-numeric between them, and a width or height of zero or less — a viewBox with a zero
     * extent disables rendering entirely, so it is not a size.
     *
     * @return the four values, or {@code null} when the attribute is missing or unusable
     */
    private static double[] viewBox(String value) {
        if (value == null) return null;

        Matcher matcher = VIEW_BOX.matcher(value);
        if (!matcher.matches()) return null;

        double x = Double.parseDouble(matcher.group(1));
        double y = Double.parseDouble(matcher.group(2));
        double width = Double.parseDouble(matcher.group(3));
        double height = Double.parseDouble(matcher.group(4));

        if (!Double.isFinite(x) || !Double.isFinite(y)) return null;
        if (!Double.isFinite(width) || !Double.isFinite(height)) return null;
        if (width <= 0 || height <= 0) return null;

        return new double[] { x, y, width, height };
    }

    /** Rounds a pair of dimensions to whole pixels, rejecting anything unusable or absurd. */
    private static int[] toSize(double width, double height) {
        if (!Double.isFinite(width) || !Double.isFinite(height)) return null;
        // Range-check before casting: Math.round returns a long, and a huge value would wrap
        // through the int cast into something small enough to look plausible.
        if (width > MAX_ICON_DIMENSION || height > MAX_ICON_DIMENSION) return null;

        int roundedWidth = (int) Math.round(width);
        int roundedHeight = (int) Math.round(height);
        return isValidSize(roundedWidth, roundedHeight)
                ? new int[] { roundedWidth, roundedHeight }
                : null;
    }

    private static boolean isValidSize(int width, int height) {
        return width > 0 && height > 0
                && width <= MAX_ICON_DIMENSION && height <= MAX_ICON_DIMENSION;
    }

    /** Player text safe to put in a log line: no control characters, bounded length. */
    private static String printable(String text) {
        if (text == null) return "null";

        StringBuilder safe = new StringBuilder(Math.min(text.length(), 40));
        for (int i = 0; i < text.length() && safe.length() < 40; i++) {
            char c = text.charAt(i);
            safe.append(Character.isISOControl(c) ? '?' : c);
        }
        if (text.length() > safe.length()) safe.append("...");
        return safe.toString();
    }
}
