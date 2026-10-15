package net.immortalmc.bluemap_sign_markers;

import de.bluecolored.bluemap.api.markers.MarkerSet;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Provides feature hooks used by various components of the plugin.
 * Implementations expose access to the BlueMap webroot, the plugin logger and
 * the central map of world -> MarkerSet associations used to store markers.
 */
public interface FeatureProvider {

    /**
     * Returns the underlying Bukkit {@link Plugin} instance for use by helpers that need
     * direct access to plugin context (scheduler, data folder, plugin description, etc.).
     *
     * @return the plugin instance
     */
    Plugin getPlugin();

    /**
     * Returns the path to the BlueMap webroot directory where static assets are served from.
     * May return {@code null} if BlueMap has not initialized yet.
     *
     * @return the Path to BlueMap's webroot, or {@code null} when unavailable
     */
    Path getWebRoot();

    /**
     * Returns the plugin logger used to record warnings, info and errors.
     *
     * @return a non-null {@link Logger} instance for the implementing plugin
     */
    Logger getLogger();

    /**
     * Returns the concurrent map that associates each loaded Bukkit {@link World} with its
     * corresponding BlueMap {@link MarkerSet}.
     * Implementations should return a thread-safe map (the project uses ConcurrentHashMap).
     *
     * @return non-null map of {@link World} to {@link MarkerSet}
     */
    Map<World, MarkerSet> getMarkerSet();

    /**
     * Persists the marker set for the given world to disk.
     * <p>
     * Called after each marker is added or removed so the saved file tracks the live state
     * rather than only being written when the plugin is disabled. Without this a crash or
     * an unclean shutdown loses every marker created since the last clean stop.
     *
     * @param world the world whose marker set should be saved
     */
    void saveMarkerSet(World world);

}
