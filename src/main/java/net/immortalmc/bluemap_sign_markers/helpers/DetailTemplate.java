package net.immortalmc.bluemap_sign_markers.helpers;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTML shown in a marker's popup, loaded from an editable file in the plugin's data folder.
 *
 * <p>The template is ordinary HTML written by the server owner. Values are substituted into
 * {@code {{ name }}} placeholders and are always HTML-escaped, so sign text can never introduce
 * markup - BlueMap assigns this string to {@code innerHTML}, and unescaped player input there
 * would execute in the browser of anyone opening the popup.
 *
 * <p>The values needed to rebuild a popup are also stored, as a base64 HTML comment appended to
 * the detail. A few of them - the individual sign lines, author and creation time - exist nowhere
 * else once the marker is written, so without this the template could not be reapplied to
 * existing markers without losing them. BlueMap neither sanitises nor strips comments, and
 * {@code detail} is a plain string that survives the plugin's save, so the comment round-trips.
 */
public final class DetailTemplate {

    /** File name, resolved against the plugin's data folder. */
    public static final String FILE_NAME = "template.html";

    /** Placeholder as written in the template: {@code {{ name }}}, spaces optional. */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}");

    /** The stored-values comment. Base64 so neither {@code --} nor {@code -->} can appear in it. */
    private static final Pattern STORED =
            Pattern.compile("<!--\\s*signmarkers:([A-Za-z0-9+/=]+)\\s*-->");

    /** Keys persisted alongside the rendered HTML, because no marker field carries them. */
    private static final String[] STORED_KEYS = { "label1", "label2", "label3", "author", "timestamp" };

    /**
     * Template written on first run.
     * <p>
     * Styles live in a {@code <style>} block rather than inline attributes, which keeps the markup
     * readable and lets the webapp's own theme variables drive the colours. A {@code <style>}
     * inserted through {@code innerHTML} is document-wide while it is attached, so the
     * {@code .bm-marker-poi-label} rule affects every POI marker while a sign-marker popup is open,
     * not only this one.
     * <p>
     * The block is duplicated into every marker's detail, so it is repeated per marker rather than
     * downloaded once. Keep it modest for large marker sets.
     */
    public static final String DEFAULT_TEMPLATE = """
            <style>
                .bm-marker-poi-label {
                    min-width: 12rem;
                    border-radius: 8px;
                }
                .smr-container {
                    text-align: center;
                    line-height: 1.4;
                }
                .smr-container hr {
                    border: none;
                    border-bottom: 1px solid;
                    border-color: var(--theme-bg-light);
                }
                .smr-label {
                    background-color: var(--theme-bg-hover);
                    border: 2px dashed var(--theme-bg-light);
                    border-radius: 8px;
                    padding: 5px;
                    margin-bottom: 10px
                }
                .smr-label-line {
                    color: var(--theme-fg-light);
                    font-size: 1.1em;
                    font-weight: bold;
                }
                .smr-info-name {
                    font-size: 0.7em;
                }
                .smr-info-value {
                    font-size: 0.8em;
                }
            </style>
            <div class="smr-container">
                <div class="smr-label">
                    <div class="smr-label-line">{{ label1 }}</div>
                    <div class="smr-label-line">{{ label2 }}</div>
                    <div class="smr-label-line">{{ label3 }}</div>
                </div>
                <div class="smr-info-name">Position (x y z):</div>
                <div class="smr-info-value">{{ position }}</div>
                <hr/>
                <div class="smr-info-name">Created {{ timestamp }}</div>
                <div class="smr-info-value">by {{ author }}</div>
            </div>
            """;

    private DetailTemplate() {
    }

    /**
     * Writes the default template if the file is absent. An existing file is never touched, so
     * edits and deliberate deletions are preserved across restarts.
     *
     * @param dataFolder the plugin's data folder
     * @param logger     logger for the creation message or a failure warning
     */
    public static void ensureTemplateFile(Path dataFolder, Logger logger) {
        Path file = dataFolder.resolve(FILE_NAME);
        if (Files.exists(file)) return;

        try {
            Files.createDirectories(dataFolder);
            Files.writeString(file, DEFAULT_TEMPLATE, StandardCharsets.UTF_8);
            logger.info("Created marker popup template at " + file);
        } catch (IOException e) {
            logger.warning("Cannot create template " + file + ": " + e.getMessage()
                    + "; the built-in default will be used");
        }
    }

    /**
     * Reads the template. Re-read per marker rather than cached, so edits take effect on the next
     * sign without a restart.
     *
     * @param dataFolder the plugin's data folder
     * @param logger     logger for a read failure
     * @return the template text, or {@link #DEFAULT_TEMPLATE} when it cannot be read
     */
    public static String readTemplate(Path dataFolder, Logger logger) {
        Path file = dataFolder.resolve(FILE_NAME);
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.isBlank()) return text;
            logger.warning("Template " + file + " is empty; using the built-in default");
        } catch (IOException e) {
            // Missing is the common case only if someone deleted it; ensureTemplateFile recreates
            // it on the next start, and markers must still be creatable in the meantime.
            logger.warning("Cannot read template " + file + " (" + e.getMessage()
                    + "); using the built-in default");
        }
        return DEFAULT_TEMPLATE;
    }

    /**
     * Substitutes {@code {{ name }}} placeholders, HTML-escaping every value.
     * <p>
     * Unknown placeholders are left as written so a typo is visible in the popup rather than
     * silently disappearing.
     *
     * @param template the template text
     * @param values   raw (unescaped) values keyed by lower-case placeholder name
     * @return the rendered HTML
     */
    public static String render(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 256);

        while (matcher.find()) {
            String value = values.get(matcher.group(1).toLowerCase(Locale.ROOT));
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    value == null ? matcher.group() : escapeHtml(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * The comment carrying the values that no marker field retains, for a later re-render.
     *
     * @param values the same map passed to {@link #render}
     * @return an HTML comment, base64-encoded
     */
    public static String storedComment(Map<String, String> values) {
        JsonObject json = new JsonObject();
        for (String key : STORED_KEYS) {
            json.addProperty(key, values.getOrDefault(key, ""));
        }

        String encoded = Base64.getEncoder()
                .encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
        return "<!-- signmarkers:" + encoded + " -->";
    }

    /**
     * Recovers the values written by {@link #storedComment}.
     *
     * @param detail a marker's detail HTML, possibly null
     * @return the stored values, or {@code null} when the detail carries none or it is corrupt
     */
    public static Map<String, String> extractStored(String detail) {
        if (detail == null) return null;

        Matcher matcher = STORED.matcher(detail);
        if (!matcher.find()) return null;

        try {
            String json = new String(Base64.getDecoder().decode(matcher.group(1)),
                    StandardCharsets.UTF_8);
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();

            Map<String, String> values = new LinkedHashMap<>();
            for (String key : STORED_KEYS) {
                values.put(key, object.has(key) ? object.get(key).getAsString() : "");
            }
            return values;
        } catch (RuntimeException e) {
            // Base64 or JSON that does not decode means the detail was edited by hand or by
            // another tool; the caller falls back to what the marker itself carries.
            return null;
        }
    }

    /** Escapes the five HTML-significant characters. Order matters: '&' must be first. */
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
