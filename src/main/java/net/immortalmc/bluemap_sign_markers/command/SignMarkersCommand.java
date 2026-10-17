package net.immortalmc.bluemap_sign_markers.command;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import net.immortalmc.bluemap_sign_markers.FeatureProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static net.immortalmc.bluemap_sign_markers.Constants.*;

/**
 * Administrative command for inspecting and repairing the marker set of the sender's world.
 *
 * <p>Signs create and remove markers automatically. This command exists for markers that have no
 * sign behind them - typically ones imported from a BlueMap config - and for inspecting what a
 * world's marker set actually contains.
 */
public class SignMarkersCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("list", "remove", "place-sign");

    /** Longest label copied onto a placed sign. A sign line is bounded; an imported label is not. */
    private static final int MAX_SIGN_LINE = 100;

    /**
     * Y correction for markers imported from a BlueMap config.
     * <p>
     * Those markers originally came from Dynmap, which records a Y one above the block it
     * describes, so a sign placed at the recorded Y lands one block too high. Markers this plugin
     * creates from a sign use the sign block's exact Y and must not be corrected - which is why
     * this is only applied to ids that do not carry {@code MARKER_ID_PREFIX}.
     */
    private static final int IMPORTED_MARKER_Y_OFFSET = -1;

    private final FeatureProvider featureProvider;

    /**
     * @param featureProvider provider exposing the plugin's marker sets, logger and world data
     */
    public SignMarkersCommand(FeatureProvider featureProvider) {
        this.featureProvider = featureProvider;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "This command must be run by a player, so the world is unambiguous");
            return true;
        }
        if (args.length == 0) return usage(sender, label);

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) return list(player);
        if (args.length < 2) return usage(sender, label);

        // Joined rather than taken as args[1]: a label contains spaces, so
        // "place-sign Land of the Abstract" has to arrive as one token. Ids never contain a
        // space, so this is a superset of taking a single argument.
        String token = String.join(" ", List.of(args).subList(1, args.length));

        return switch (sub) {
            case "remove" -> remove(player, token);
            case "place-sign" -> placeSign(player, token);
            default -> usage(sender, label);
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(name -> name.startsWith(typed)).toList();
        }
        if (args.length >= 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (!sub.equals("remove") && !sub.equals("place-sign")) return List.of();
            if (!(sender instanceof Player player)) return List.of();

            MarkerSet set = markerSetFor(player);
            if (set == null) return List.of();

            // The typed token is everything after the subcommand, so a partly-typed multi-word
            // label still filters sensibly. Both ids and labels are offered: the id is the
            // unambiguous fallback when two markers share a label.
            String typed = String.join(" ", List.of(args).subList(1, args.length))
                    .toLowerCase(Locale.ROOT);

            return set.getMarkers().entrySet().stream()
                    .flatMap(entry -> Stream.of(entry.getKey(), entry.getValue().getLabel()))
                    .filter(candidate -> !candidate.isBlank())
                    .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(typed))
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // Subcommands
    // ------------------------------------------------------------------

    /** Lists every marker in the sender's world, flagging which have no sign behind them. */
    private boolean list(Player player) {
        MarkerSet set = markerSetFor(player);
        String world = player.getWorld().getName();

        if (set == null || set.getMarkers().isEmpty()) {
            send(player, "No markers in " + world);
            return true;
        }

        send(player, set.getMarkers().size() + " markers in " + world + ":");
        set.getMarkers().entrySet().stream()
                .sorted((a, b) -> a.getValue().getLabel().compareToIgnoreCase(b.getValue().getLabel()))
                .forEach(entry -> {
                    String id = entry.getKey();
                    Marker marker = entry.getValue();
                    Vector3d pos = marker.getPosition();
                    // Ids this plugin builds are coordinate-derived, so a sign backs them.
                    // Anything else came from a config import and has no block behind it.
                    String kind = id.startsWith(MARKER_ID_PREFIX) ? "sign" : "no sign";
                    send(player, String.format("%s  [%s]  %s  @ %d,%d,%d",
                            marker.getLabel(), kind, id,
                            (int) Math.floor(pos.getX()),
                            (int) Math.floor(pos.getY()),
                            (int) Math.floor(pos.getZ())));
                });
        return true;
    }

    /** Removes a single marker by id, whatever its origin. */
    private boolean remove(Player player, String token) {
        MarkerSet set = markerSetFor(player);
        if (set == null) {
            send(player, "No marker set in " + player.getWorld().getName());
            return true;
        }

        String id = findMarkerId(player, set, token);
        if (id == null) return true;

        Marker marker = set.get(id);
        set.remove(id);
        featureProvider.saveMarkerSet(player.getWorld());
        send(player, "Removed '" + marker.getLabel() + "' (" + id + ")");
        return true;
    }

    /**
     * Places a sign at a marker's position so the marker becomes sign-backed, then re-keys it to the
     * id a block break will look for.
     * <p>
     * The re-key is the point of the command: block-break removal recomputes the id from the block
     * coordinates, so a marker still called {@code poi_poi1} would never be found and the sign would
     * appear to do nothing.
     */
    private boolean placeSign(Player player, String token) {
        MarkerSet set = markerSetFor(player);
        if (set == null) {
            send(player, "No marker set in " + player.getWorld().getName());
            return true;
        }

        String id = findMarkerId(player, set, token);
        if (id == null) return true;

        Marker marker = set.get(id);

        Vector3d position = marker.getPosition();
        int x = (int) Math.floor(position.getX());
        int z = (int) Math.floor(position.getZ());

        // Imported markers store a Y one above the block; see IMPORTED_MARKER_Y_OFFSET.
        boolean imported = !id.startsWith(MARKER_ID_PREFIX);
        int y = (int) Math.floor(position.getY()) + (imported ? IMPORTED_MARKER_Y_OFFSET : 0);

        World world = player.getWorld();
        Block block = world.getBlockAt(x, y, z);

        // Placing a block destroys whatever is already there, so refuse rather than overwrite.
        if (!block.getType().isAir()) {
            send(player, "Won't place a sign at " + x + " " + y + " " + z
                    + ": " + block.getType() + " is already there");
            return true;
        }

        String canonicalId = markerIdAt(block.getX(), block.getY(), block.getZ());
        if (!canonicalId.equals(id) && set.get(canonicalId) != null) {
            send(player, "A marker already exists at " + canonicalId + "; nothing placed");
            return true;
        }

        block.setType(Material.OAK_SIGN);
        Sign sign = (Sign) block.getState();
        SignSide front = sign.getSide(Side.FRONT);
        front.line(0, LegacyComponentSerializer.legacySection().deserialize(MARKER_PLACEHOLDER));
        front.line(1, Component.text(signLine(marker.getLabel())));
        sign.update();

        // Pull the marker's own position down to the block it now sits on. Without this the data
        // keeps the uncorrected Y, and a second run would apply the correction again.
        marker.setPosition(new Vector3d(x, y, z));

        set.remove(id);
        set.put(canonicalId, marker);
        featureProvider.saveMarkerSet(world);

        send(player, "Placed a sign at " + x + " " + y + " " + z
                + (canonicalId.equals(id) ? "" : "; marker re-keyed to '" + canonicalId + "'"));
        return true;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private MarkerSet markerSetFor(Player player) {
        return featureProvider.getMarkerSet().get(player.getWorld());
    }

    /**
     * Resolves an id or a label to a marker id, so either can be typed.
     * <p>
     * An exact id always wins. Otherwise the token is matched against labels case-insensitively;
     * labels are not guaranteed unique, so an ambiguous one resolves to nothing and every
     * candidate id is reported rather than guessing and acting on the wrong marker.
     *
     * @param player the sender, used only for feedback
     * @param set    the marker set to search
     * @param token  a marker id or a marker label
     * @return the matching marker id, or {@code null} when nothing matched or the label was
     *         ambiguous (in which case the player has already been told why)
     */
    private String findMarkerId(Player player, MarkerSet set, String token) {
        if (set.get(token) != null) return token;

        List<String> matches = set.getMarkers().entrySet().stream()
                .filter(entry -> entry.getValue().getLabel().equalsIgnoreCase(token))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        if (matches.isEmpty()) {
            send(player, "No marker with id or label '" + token + "'");
            return null;
        }
        if (matches.size() > 1) {
            send(player, "'" + token + "' matches " + matches.size()
                    + " markers; use an id instead: " + String.join(", ", matches));
            return null;
        }
        return matches.get(0);
    }

    private boolean usage(CommandSender sender, String label) {
        send(sender, "Usage: /" + label + " <list|remove|place-sign> [marker-id]");
        return true;
    }

    /** Truncates a marker label to something a sign line can hold. */
    private static String signLine(String text) {
        if (text == null) return "";
        return text.length() <= MAX_SIGN_LINE
                ? text
                : text.substring(0, MAX_SIGN_LINE - 3) + "...";
    }

    /** Sends a message in the plugin's usual chat style. */
    private void send(CommandSender sender, String message) {
        // Sent as a Component rather than legacy section codes: Paper's CommandSender is an
        // Audience, so the string form is only there for backward compatibility.
        sender.sendMessage(MiniMessage.miniMessage().deserialize(String.format(MSG_PREFIX, message)));
    }
}
