package net.immortalmc.bluemap_sign_markers.watcher;

import com.flowpowered.math.vector.Vector3d;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Simple data holder describing marker content (three label lines, position and metadata).
 * Includes a nested {@code Builder} to construct instances with a fluent API.
 */
public class MarkerContent {

    private String label1;
    private String label2;
    private String label3;
    private long timestampMillis;
    private String author;
    private Vector3d position;

    private static final String DATE_FORMAT = "dd MMM yyyy HH:mm";

    /**
     * Private constructor used by the Builder.
     */
    private MarkerContent() {
    }

    /**
     * Creates a new {@link Builder} for {@link MarkerContent}.
     *
     * @return a fresh Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    // Getters
    /**
     * Returns the first label line for the marker.
     *
     * @return first label line, possibly empty or {@code null}
     */
    public String getLabel1() {
        return label1;
    }

    /**
     * Returns the second label line for the marker.
     *
     * @return second label line, possibly empty or {@code null}
     */
    public String getLabel2() {
        return label2;
    }

    /**
     * Returns the third label line for the marker.
     *
     * @return third label line, possibly empty or {@code null}
     */
    public String getLabel3() {
        return label3;
    }

    /**
     * Returns the marker's X coordinate (floor value).
     *
     * @return X coordinate as an integer
     */
    public int getX() {
        return position.getFloorX();
    }

    /**
     * Returns the marker's Y coordinate (floor value).
     *
     * @return Y coordinate as an integer
     */
    public int getY() {
        return position.getFloorY();
    }

    /**
     * Returns the marker's Z coordinate (floor value).
     *
     * @return Z coordinate as an integer
     */
    public int getZ() {
        return position.getFloorZ();
    }

    /**
     * Returns the author who created the marker.
     *
     * @return author name, or {@code null} if unknown
     */
    public String getAuthor() {
        return author;
    }

    /**
     * Returns a formatted timestamp string for when the marker was created.
     *
     * @return formatted timestamp ("dd MMM yyyy HH:mm") or empty string if timestamp is unset
     */
    public String getTimestamp() {
        return formatTimestamp(timestampMillis);
    }

    /**
     * Formats epoch milliseconds for display, so the format is defined once for both markers
     * created from signs and markers re-attributed when their template is reapplied.
     *
     * @param millis epoch milliseconds
     * @return the formatted timestamp, or an empty string when {@code millis} is not positive
     */
    public static String formatTimestamp(long millis) {
        if (millis <= 0) return "";
        Instant instant = Instant.ofEpochMilli(millis);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(DATE_FORMAT, Locale.ENGLISH).withZone(ZoneId.systemDefault());
        return fmt.format(instant);
    }

    /**
     * Builder for {@link MarkerContent} providing a fluent API.
     */
    public static class Builder {
        private final MarkerContent instance;

        private Builder() {
            instance = new MarkerContent();
        }

        /**
         * Sets the first label line.
         *
         * @param label1 first label
         * @return this builder
         */
        public Builder label1(String label1) {
            instance.label1 = label1;
            return this;
        }

        /**
         * Sets the second label line.
         *
         * @param label2 second label
         * @return this builder
         */
        public Builder label2(String label2) {
            instance.label2 = label2;
            return this;
        }

        /**
         * Sets the third label line.
         *
         * @param label3 third label
         * @return this builder
         */
        public Builder label3(String label3) {
            instance.label3 = label3;
            return this;
        }

        /**
         * Sets the World position of the marker.
         *
         * @param position {@link Vector3d}
         * @return this builder
         */
        public Builder position(Vector3d position) {
            instance.position = position;
            return this;
        }

        /**
         * Sets the author of the marker.
         *
         * @param author author name
         * @return this builder
         */
        public Builder author(String author) {
            instance.author = author;
            return this;
        }


        /**
         * Builds the {@link MarkerContent} instance.
         *
         * @return a fully constructed MarkerContent
         */
        public MarkerContent build() {
            instance.timestampMillis = System.currentTimeMillis();
            return instance;
        }
    }
}
