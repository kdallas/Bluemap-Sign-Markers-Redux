package net.immortalmc.bluemap_sign_markers.command;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import net.immortalmc.bluemap_sign_markers.FeatureProvider;
import net.immortalmc.bluemap_sign_markers.helpers.DetailTemplate;
import net.immortalmc.bluemap_sign_markers.helpers.IconResolver;
import net.immortalmc.bluemap_sign_markers.watcher.MarkerContent;
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

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static net.immortalmc.bluemap_sign_markers.Constants.*;

/**
 * Administrative command for inspecting and repairing the marker set of the sender's world.
 *
 * <p>Signs create and remove markers automatically. This command exists for markers that have no
 * sign behind them - typically ones imported from a BlueMap config - and for inspecting what a
 * world's marker set actually contains.
 */
public class SignMarkersCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("list", "remove", "place-sign", "reapply", "regenerate");

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

        // Joined rather than taken as args[1]: a label contains spaces, so
        // "place-sign Land of the Abstract" has to arrive as one token. Ids never contain a
        // space, so this is a superset of taking a single argument. Null when nothing was given,
        // which only "reapply" treats as meaningful - it then applies to the whole world.
        String token = args.length < 2
                ? null
                : String.join(" ", List.of(args).subList(1, args.length));

        return switch (sub) {
            case "reapply" -> token == null ? reapplyAll(player) : reapplyOne(player, token);
            case "regenerate" -> token == null ? regenerateAll(player) : regenerateOne(player, token);
            case "remove" -> token == null ? usage(sender, label) : remove(player, token);
            case "place-sign" -> token == null ? usage(sender, label) : placeSign(player, token);
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
            if (!sub.equals("remove") && !sub.equals("place-sign")
                    && !sub.equals("reapply") && !sub.equals("regenerate")) {
                return List.of();
            }
            if (!(sender instanceof Player player)) return List.of();

            MarkerSet set = markerSetFor(player);
            if (set == null) return List.of();

            // The typed token is everything after the subcommand, so a partly-typed multi-word
            // label still filters sensibly.
            String typed = String.join(" ", List.of(args).subList(1, args.length))
                    .toLowerCase(Locale.ROOT);

            // One entry per marker. The label is preferred because it is what the player
            // recognises, but an id is suggested when there is no usable label - a label that is
            // blank, or one shared with another marker. findMarkerId refuses an ambiguous label
            // rather than guessing, so suggesting it would only lead to that refusal.
            // Counted case-insensitively to match how labels are resolved.
            Map<String, Long> labelCounts = set.getMarkers().values().stream()
                    .map(marker -> marker.getLabel().toLowerCase(Locale.ROOT))
                    .collect(Collectors.groupingBy(label -> label, Collectors.counting()));

            return set.getMarkers().entrySet().stream()
                    .map(entry -> {
                        String label = entry.getValue().getLabel();
                        boolean usable = !label.isBlank()
                                && labelCounts.getOrDefault(label.toLowerCase(Locale.ROOT), 0L) == 1L;
                        return usable ? label : entry.getKey();
                    })
                    .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(typed))
                    .distinct()
                    // Deliberately unsorted. Minecraft's suggestion builder alphabetises this list
                    // after the plugin returns it, so any order set here is gone before the packet
                    // is sent (PaperMC/Paper#12787). Only the list command controls its ordering,
                    // because we render that ourselves.
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
                .sorted((a, b) -> {
                    int byTitle = sortKey(a.getValue().getLabel())
                            .compareTo(sortKey(b.getValue().getLabel()));
                    // Equal keys ("The Beach" and "Beach") fall back to the full label so the
                    // order is deterministic rather than dependent on map iteration.
                    return byTitle != 0 ? byTitle
                            : a.getValue().getLabel().compareToIgnoreCase(b.getValue().getLabel());
                })
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

    /**
     * Re-renders every marker's popup with the current template.
     * <p>
     * The detail is baked in when a marker is created, so editing the template only affects
     * markers made afterwards. This rebuilds the existing ones from the values stored alongside
     * their detail. Markers without those values - imported from a config, or whose detail was
     * edited by hand - fall back to what the marker itself carries, which has no individual sign
     * lines, author or creation time to restore.
     */
    private boolean reapplyAll(Player player) {
        MarkerSet set = markerSetFor(player);
        String world = player.getWorld().getName();

        if (set == null || set.getMarkers().isEmpty()) {
            send(player, "No markers in " + world);
            return true;
        }

        String template = currentTemplate();
        int updated = 0;
        int withoutStoredValues = 0;
        for (Marker marker : set.getMarkers().values()) {
            if (!(marker instanceof POIMarker poi)) continue;
            if (!reapplyMarker(poi, template, world, player)) withoutStoredValues++;
            updated++;
        }

        featureProvider.saveMarkerSet(player.getWorld());
        send(player, "Re-rendered " + updated + " markers in " + world
                + (withoutStoredValues == 0 ? ""
                : "; " + withoutStoredValues + " had no stored sign lines and used the joined label"));
        return true;
    }

    /** Re-renders a single marker, leaving the rest of the set untouched. */
    private boolean reapplyOne(Player player, String token) {
        MarkerSet set = markerSetFor(player);
        if (set == null) {
            send(player, "No marker set in " + player.getWorld().getName());
            return true;
        }

        String id = findMarkerId(player, set, token);
        if (id == null) return true;

        Marker marker = set.get(id);
        if (!(marker instanceof POIMarker poi)) {
            send(player, "'" + id + "' is not a POI marker, so it has no popup template");
            return true;
        }

        boolean hadStoredValues =
                reapplyMarker(poi, currentTemplate(), player.getWorld().getName(), player);
        featureProvider.saveMarkerSet(player.getWorld());
        send(player, "Re-rendered '" + poi.getLabel() + "' (" + id + ")"
                + (hadStoredValues ? "" : " - no stored sign lines, so the joined label was used"));
        return true;
    }

    /**
     * Rebuilds one marker's popup from the stored values, falling back to what the marker itself
     * carries when it has none.
     */
    private boolean reapplyMarker(POIMarker poi, String template, String world, Player by) {
        Map<String, String> values = DetailTemplate.extractStored(poi.getDetail());
        boolean restoredLabels = values != null;
        if (values == null) {
            // No comment at all: an imported marker, or one predating the stored values. The
            // joined label is all that survives, so it becomes the first sign line.
            values = new LinkedHashMap<>();
            values.put("label1", poi.getLabel());
            values.put("label2", "");
            values.put("label3", "");
        }

        // Always stamped, never restored: a reapply is an edit, so the popup records who last
        // rendered it and when, rather than preserving a creator that may never have existed.
        values.put("author", by.getName());
        values.put("timestamp", MarkerContent.formatTimestamp(System.currentTimeMillis()));

        Vector3d pos = poi.getPosition();
        int x = (int) Math.floor(pos.getX());
        int y = (int) Math.floor(pos.getY());
        int z = (int) Math.floor(pos.getZ());

        values.put("label", poi.getLabel());
        values.put("x", String.valueOf(x));
        values.put("y", String.valueOf(y));
        values.put("z", String.valueOf(z));
        values.put("position", x + " " + y + " " + z);
        values.put("icon", poi.getIconAddress() == null ? "" : poi.getIconAddress());
        values.put("world", world);

        poi.setDetail(DetailTemplate.render(template, values) + DetailTemplate.storedComment(values));
        return restoredLabels;
    }

    private String currentTemplate() {
        Path dataFolder = featureProvider.getPlugin().getDataFolder().toPath();
        return DetailTemplate.readTemplate(dataFolder, featureProvider.getLogger());
    }

    /**
     * Rebuilds every marker's popup from its sign where one exists, falling back to the stored
     * data otherwise.
     */
    private boolean regenerateAll(Player player) {
        MarkerSet set = markerSetFor(player);
        String world = player.getWorld().getName();

        if (set == null || set.getMarkers().isEmpty()) {
            send(player, "No markers in " + world);
            return true;
        }

        String template = currentTemplate();
        int updated = 0;
        int fromSign = 0;
        for (Marker marker : set.getMarkers().values()) {
            if (!(marker instanceof POIMarker poi)) continue;
            if (regenerateMarker(poi, player.getWorld(), template, player)) fromSign++;
            updated++;
        }

        featureProvider.saveMarkerSet(player.getWorld());
        send(player, "Regenerated " + updated + " markers in " + world
                + "; " + fromSign + " rebuilt from a sign");
        return true;
    }

    /** Rebuilds a single marker from its sign where one exists. */
    private boolean regenerateOne(Player player, String token) {
        MarkerSet set = markerSetFor(player);
        if (set == null) {
            send(player, "No marker set in " + player.getWorld().getName());
            return true;
        }

        String id = findMarkerId(player, set, token);
        if (id == null) return true;

        Marker marker = set.get(id);
        if (!(marker instanceof POIMarker poi)) {
            send(player, "'" + id + "' is not a POI marker, so it has no popup template");
            return true;
        }

        boolean fromSign = regenerateMarker(poi, player.getWorld(), currentTemplate(), player);
        featureProvider.saveMarkerSet(player.getWorld());
        send(player, "Regenerated '" + poi.getLabel() + "' (" + id + ")"
                + (fromSign ? "" : " - no sign at that position, so the stored lines were used"));
        return true;
    }

    /**
     * Rebuilds one marker from the sign at its position, if there is one.
     * <p>
     * A superset of {@link #reapplyMarker}: where that re-renders what the marker already holds,
     * this also picks up edits made to the sign afterwards. Those are otherwise invisible - once
     * a marker exists, line 0 holds the plugin's placeholder rather than an icon token, so
     * {@code onSignWrite} returns early and never notices a change to lines 1-3.
     * <p>
     * When a sign is found, its icon token (if line 0 still carries one), label and lines are all
     * taken from it. Otherwise the marker falls back to its stored data, exactly as reapply does.
     *
     * @return true when a sign was found and used
     */
    private boolean regenerateMarker(POIMarker poi, World world, String template, Player by) {
        Vector3d position = poi.getPosition();
        int x = (int) Math.floor(position.getX());
        int y = (int) Math.floor(position.getY());
        int z = (int) Math.floor(position.getZ());

        String label1 = null;
        String label2 = null;
        String label3 = null;
        IconResolver.IconRef resolvedIcon = null;

        if (world.getBlockAt(x, y, z).getState() instanceof Sign sign) {
            SignSide front = sign.getSide(Side.FRONT);

            String line0 = plain(front.line(0)).trim();
            if (line0.startsWith("[") && line0.endsWith("]")) {
                String token = line0.substring(1, line0.length() - 1).toLowerCase(Locale.ROOT);
                resolvedIcon = IconResolver.resolve(
                        featureProvider.getWebRoot(), token, featureProvider.getLogger());
            }

            label1 = plain(front.line(1));
            label2 = plain(front.line(2));
            label3 = plain(front.line(3));
        }

        boolean fromSign = label1 != null;

        Map<String, String> values;
        if (fromSign) {
            values = new LinkedHashMap<>();
            values.put("label1", label1);
            values.put("label2", label2);
            values.put("label3", label3);
        } else {
            values = DetailTemplate.extractStored(poi.getDetail());
            if (values == null) {
                values = new LinkedHashMap<>();
                values.put("label1", poi.getLabel());
                values.put("label2", "");
                values.put("label3", "");
            }
        }

        // Same policy as reapply: stamped with whoever ran this, never restored.
        values.put("author", by.getName());
        values.put("timestamp", MarkerContent.formatTimestamp(System.currentTimeMillis()));

        // Bring the marker itself into line with the sign, so the name shown in BlueMap's marker
        // list matches the popup rather than staying at whatever it was created with.
        if (fromSign) {
            String joined = joinLabels(label1, label2, label3);
            if (!joined.isBlank()) poi.setLabel(joined);
            if (resolvedIcon != null) poi.setIcon(resolvedIcon.path(), resolvedIcon.anchor());
        }

        values.put("label", poi.getLabel());
        values.put("x", String.valueOf(x));
        values.put("y", String.valueOf(y));
        values.put("z", String.valueOf(z));
        values.put("position", x + " " + y + " " + z);
        values.put("icon", poi.getIconAddress() == null ? "" : poi.getIconAddress());
        values.put("world", world.getName());

        poi.setDetail(DetailTemplate.render(template, values) + DetailTemplate.storedComment(values));
        return fromSign;
    }

    /** A sign line as the plain text the marker stores, matching how SignWatcher reads them. */
    private static String plain(Component component) {
        return component == null
                ? ""
                : LegacyComponentSerializer.legacySection().serialize(component);
    }

    /** Joins the label lines the same way a marker created from the sign would. */
    private static String joinLabels(String... lines) {
        return List.of(lines).stream()
                .filter(line -> line != null && !line.isBlank())
                .collect(Collectors.joining(" "));
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
        send(sender, "Usage: /" + label + " <list|remove|place-sign|reapply|regenerate> [marker-id]");
        return true;
    }

    /**
     * Leading articles ignored when ordering. Lower-cased, and the trailing space matters: a
     * label that merely starts with "A" such as "Ahoy Shipwreck" is not an article.
     */
    private static final List<String> SORT_ARTICLES = List.of("the ", "an ", "a ");

    /**
     * Sort key for a label, ignoring a leading article and case, so titles order the way a reader
     * expects: "The Beach" sorts with B rather than with T, and "The Rift" with R.
     * <p>
     * Only the ordering is affected - the label itself is never changed, so resolution by label
     * still requires what is actually stored.
     *
     * @param label the marker label
     * @return the comparison key, never null
     */
    private static String sortKey(String label) {
        String key = label == null ? "" : label.trim();
        String lower = key.toLowerCase(Locale.ROOT);

        for (String article : SORT_ARTICLES) {
            // The length check keeps a label of exactly "The" from reducing to nothing.
            if (lower.startsWith(article) && lower.length() > article.length()) {
                key = key.substring(article.length()).trim();
                break;
            }
        }
        return key.toLowerCase(Locale.ROOT);
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
