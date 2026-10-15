package net.immortalmc.bluemap_sign_markers.watcher;


import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import net.immortalmc.bluemap_sign_markers.FeatureProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import static net.immortalmc.bluemap_sign_markers.Constants.*;

/**
 * Listener that watches for sign creation and destruction events and manages
 * corresponding BlueMap markers based on sign contents.
 * Behavior summary:
 * - When a player writes a sign with a recognized icon tag on the first line (e.g. "[map]")
 * the listener will attempt to create a POI marker on BlueMap using the chosen icon and
 * the text from lines 1-3 as the marker label/details.
 * - When a sign is broken, the listener will remove the marker with the corresponding
 * coordinate-based id from the marker set for that world.
 */
public class SignWatcher implements Listener {

    /**
     * FeatureProvider used to access plugin-level facilities:
     * - BlueMap webroot path (for icons)
     * - plugin {@link java.util.logging.Logger}
     * - the central map of {@link org.bukkit.World} -> {@link de.bluecolored.bluemap.api.markers.MarkerSet}
     */
    private final FeatureProvider featureProvider;

    /**
     * Constructs a SignWatcher using the provided FeatureProvider to access plugin features.
     *
     * @param featureProvider provider exposing BlueMap webroot, logger and marker map
     */
    public SignWatcher(FeatureProvider featureProvider) {
        this.featureProvider = featureProvider;
    }

    /**
     * Handles sign change events (player writes a sign).
     * <p>
     * Expected workflow:
     * - Reads the first line and attempts to match an icon token (e.g. "[map]").
     * - If an icon is found and its image exists in BlueMap's webroot, constructs a POI marker
     * using the sign's position and lines 1-3 as label/detail.
     * - Stores the marker in the marker map under an id built from the block coordinates.
     * - Replaces the first line of the sign with a marker indicator and notifies the player.
     * If image reading or file access fails, the error is logged and the marker is not created.
     *
     * @param event the SignChangeEvent triggered when a player finalizes sign text
     */
    @EventHandler
    public void onSignWrite(SignChangeEvent event) {
        String iconName;

        // ### Mapping sign's line 0 to specific marker type (translates to icon)
        final String line0 = readLine(event, 0);
        if (line0 != null && !line0.isBlank() && line0.startsWith("[") && line0.endsWith("]")) {
            String rawIconName = line0.replaceAll("^\\[(.*)]$", "$1").toLowerCase();
            String iconFileName =  IMAGE_PATH + rawIconName + ".png";
            File tmpIconFile = new File(featureProvider.getWebRoot() + "/" + iconFileName);
            
            if (tmpIconFile.exists()) {
                iconName = rawIconName;
            } else {
                iconName = "ban_white";
                
            };
        } else {
            return;
        };
        String icon = IMAGE_PATH + iconName + ".png";
        File iconFile = new File(featureProvider.getWebRoot() + "/" + icon);
        

        Vector2i anchor;
        try {
            BufferedImage image = ImageIO.read(iconFile);
            int width = image.getWidth();
            int height = image.getHeight();
            anchor = new Vector2i(height / 2, width / 2);
        } catch (IOException e) {
            featureProvider.getLogger().warning(String.format("Something wrong with image %s, details: %s", iconFile.getPath(), e.getMessage()));
            return;
        }


        // ### Building label for the sign, from lines 1-3
        String label1 = readLine(event, 1);
        String label2 = readLine(event, 2);
        String label3 = readLine(event, 3);

        String fullLabel = label1 + " " + label2 + " " + label3;

        // ### No description - no marker
        if (fullLabel.isBlank()) return;

        // ### Getting sign's block XYZ position. This will be marker's id
        Block block = event.getBlock();
        Vector3d pos = new Vector3d(block.getX(), block.getY(), block.getZ());

        String id = MARKER_ID_PREFIX + pos.getX() + "-" + pos.getY() + "-" + pos.getZ();
        MarkerContent markerContent = MarkerContent.builder()
                .label1(label1)
                .label2(label2)
                .label3(label3)
                .position(pos)
                .author(event.getPlayer().getName())
                .build();
        String markerDetails = buildMarkerContent(markerContent);
        POIMarker marker = POIMarker.builder().position(pos).label(fullLabel).icon(icon, anchor).maxDistance(100000).detail(markerDetails).build();
        featureProvider.getMarkerSet().get(block.getWorld()).put(id, marker);

        // ### Replace first line, with prefix, e.g. [map], to <marker> indicator
        event.line(0, LegacyComponentSerializer.legacySection().deserialize(MARKER_PLACEHOLDER));
        event.getPlayer().sendMessage(formatMessage(String.format(ADDED_TEMPLATE, iconName, pos.getFloorX(), pos.getFloorY(), pos.getFloorZ())));
    }

    /**
     * Handles block break events to remove markers when signs are destroyed.
     * If the broken block is a sign and a marker with the corresponding coordinate-based id
     * exists in the marker set, the marker will be removed and the player will be notified.
     *
     * @param event the BlockBreakEvent representing the block destruction
     */
    @EventHandler
    public void onSignDestroy(BlockBreakEvent event) {

        Block block = event.getBlock();
        if (!(block.getState() instanceof Sign)) return;

        MarkerSet set = featureProvider.getMarkerSet().get(block.getWorld());
        if (set == null) return;

        Vector3d pos = new Vector3d(block.getX(), block.getY(), block.getZ());
        String id = MARKER_ID_PREFIX + pos.getX() + "-" + pos.getY() + "-" + pos.getZ();

        Marker marker = set.get(id);
        if (marker == null) return;
        set.remove(id);

        event.getPlayer().sendMessage(formatMessage(String.format(REMOVED_TEMPLATE, (int) pos.getX(), (int) pos.getY(), (int) pos.getZ())));
    }

    /**
     * Builds the HTML content for a marker's detail popup using the provided information.
     *
     * @param content {@link MarkerContent} object containing label lines, position, timestamp and author
     * @return HTML string suitable for use as the marker detail content (ready to be passed to BlueMap)
     */
    private String buildMarkerContent(MarkerContent content) {
        return String.format(HTML_TEMPLATE,
                content.getLabel1(),
                content.getLabel2(),
                content.getLabel3(),
                content.getX(),
                content.getY(),
                content.getZ(),
                content.getTimestamp(),
                content.getAuthor());
    }

    /**
     * Formats a message using MiniMessage/Legacy templates for player feedback.
     *
     * @param message the message text to format
     * @return an Adventure {@link String} ready to send to a player
     */
    private String formatMessage(String message) {
        return LegacyComponentSerializer.legacySection()
                .serialize(MiniMessage.miniMessage().deserialize(String.format(MSG_PREFIX, message)));
    }

    /**
     * Reads a single line from the sign involved in a {@link SignChangeEvent}.
     * <p>
     * The event's line accessors are Adventure {@link Component}-based. The icon tag on line 0 and
     * the label lines are matched as legacy text, so the component is serialized back into the
     * legacy String form the rest of this class works with.
     *
     * @param event the sign change event
     * @param index the line index to read (0-3)
     * @return the line's text, or an empty string when the line is unset
     */
    private String readLine(SignChangeEvent event, int index) {
        Component line = event.line(index);
        return line == null ? "" : LegacyComponentSerializer.legacySection().serialize(line);
    }
}