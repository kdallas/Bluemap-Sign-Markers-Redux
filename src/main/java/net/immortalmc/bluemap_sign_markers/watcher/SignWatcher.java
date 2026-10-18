package net.immortalmc.bluemap_sign_markers.watcher;


import net.immortalmc.bluemap_sign_markers.helpers.MarkerService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;

import java.util.Locale;

import static net.immortalmc.bluemap_sign_markers.Constants.*;

/**
 * Listener that watches for sign creation and destruction events and manages
 * corresponding BlueMap markers based on sign contents.
 * Behavior summary:
 * - When a player writes a sign with a recognized icon tag on the first line (e.g. "[map]")
 * the listener will create a POI marker on BlueMap using the chosen icon and
 * the text from lines 1-3 as the marker label/details.
 * - When a sign is broken, the listener will remove the marker with the corresponding
 * coordinate-based id from the marker set for that world.
 */
public class SignWatcher implements Listener {

    /**
     * Creates and removes markers. Shared with the book input listener so a sign produces the
     * same marker whichever way its text was entered.
     */
    private final MarkerService markerService;

    /**
     * Constructs a SignWatcher using the service that owns marker creation.
     *
     * @param markerService creates and removes markers
     */
    public SignWatcher(MarkerService markerService) {
        this.markerService = markerService;
    }

    /**
     * Handles sign change events (player writes a sign).
     * <p>
     * Expected workflow:
     * - Reads the first line and attempts to match an icon token (e.g. "[map]").
     * - Delegates marker creation to {@link MarkerService}, which resolves the icon and uses
     * lines 1-3 as label/details.
     * - Replaces the first line of the sign with a marker indicator and notifies the player.
     * If the icon cannot be resolved, the bundled fallback icon is used. If it exists but its size
     * cannot be determined, the marker is still created with a default anchor and a warning is
     * logged.
     *
     * @param event the SignChangeEvent triggered when a player finalizes sign text
     */
    @EventHandler
    public void onSignWrite(SignChangeEvent event) {
        // ### Mapping sign's line 0 to specific marker type (translates to icon)
        final String line0 = readLine(event, 0);
        if (line0 == null || line0.isBlank() || !line0.startsWith("[") || !line0.endsWith("]")) {
            return;
        }

        // Locale.ROOT: the default-locale toLowerCase() maps "I" to a dotless "i" under a Turkish
        // locale, so "[ICON]" would stop matching icon.png. IconResolver trims the token, rejects
        // anything that is not a plain file name, and never returns null: an icon that exists but
        // cannot be measured is used with a guessed anchor rather than being refused.
        final String token = line0.substring(1, line0.length() - 1).toLowerCase(Locale.ROOT);

        // ### Building label for the sign, from lines 1-3
        String label1 = readLine(event, 1);
        String label2 = readLine(event, 2);
        String label3 = readLine(event, 3);

        Block block = event.getBlock();
        MarkerService.Result result = markerService.createOrUpdate(
                block.getWorld(), block.getX(), block.getY(), block.getZ(),
                token, label1, label2, label3, event.getPlayer().getName());
        // No text on lines 1-3, or no marker set for this world: leave the sign untouched.
        if (result == null) return;

        // ### Replace first line, with prefix, e.g. [map], to <marker> indicator
        event.line(0, LegacyComponentSerializer.legacySection().deserialize(MARKER_PLACEHOLDER));
        String template = result.replaced() ? UPDATED_TEMPLATE : ADDED_TEMPLATE;
        event.getPlayer().sendMessage(formatMessage(String.format(template,
                result.icon().name(), block.getX(), block.getY(), block.getZ())));
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

        if (markerService.remove(block.getWorld(), block.getX(), block.getY(), block.getZ()) == null) {
            return;
        }

        event.getPlayer().sendMessage(formatMessage(String.format(REMOVED_TEMPLATE,
                block.getX(), block.getY(), block.getZ())));
    }

    /**
     * Formats a message using MiniMessage/Legacy templates for player feedback.
     *
     * @param message the message text to format
     * @return an Adventure {@link String} ready to be sent to a player
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
