package net.immortalmc.bluemap_sign_markers;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import net.immortalmc.bluemap_sign_markers.helpers.IconHelper;
import net.immortalmc.bluemap_sign_markers.helpers.MarkerHelper;
import net.immortalmc.bluemap_sign_markers.watcher.SignWatcher;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Main plugin handling sign markers for BlueMap.
 * Responsible for initialization, loading/saving marker sets and copying icons to BlueMap's webroot.
 */
public class SignMarkers extends JavaPlugin implements FeatureProvider {

    /**
     * Path to BlueMap's webroot. Populated when BlueMap API becomes available.
     * May be {@code null} before BlueMap initializes.
     */
    private Path webRoot;

    /**
     * Plugin logger instance. Initialized from {@link JavaPlugin#getLogger()} on enable.
     */
    private Logger logger;

    /**
     * Helper instance that manages marker persistence and lifecycle operations.
     */
    private MarkerHelper markerHelper;

    /**
     * Whether the {@link SignWatcher} listener has been registered.
     * <p>
     * BlueMap re-fires its {@code onEnable} consumers every time it is reloaded, so
     * registering unconditionally would add another listener per reload. Bukkit dispatches
     * each sign event to every registered listener, so the player feedback would repeat
     * once per accumulated reload. The listener itself survives a BlueMap reload, so it
     * only ever needs registering once.
     */
    private boolean signWatcherRegistered = false;

    /**
     * Concurrent map associating each loaded Bukkit {@link World} with its BlueMap {@link MarkerSet}.
     */
    private final Map<World, MarkerSet> markerSet = new ConcurrentHashMap<>();

    /**
     * Called when the plugin is enabled.
     * <p>
     * This method performs the following actions:
     * <ul>
     *     <li>initializes the logger;</li>
     *     <li>creates data files for each world;</li>
     *     <li>loads existing marker sets and registers them with BlueMap;</li>
     *     <li>copies marker icons from the plugin JAR into BlueMap's webroot;</li>
     *     <li>registers the sign watcher (SignWatcher).</li>
     * </ul>
     * I/O errors are handled locally and logged as warnings.
     */
    @Override
    public void onEnable() {
        logger = getLogger();
        markerHelper = new MarkerHelper(this);
        BlueMapAPI.onEnable(api -> {
                    try {
                        JarFile jar = new JarFile(this.getFile());
                        webRoot = api.getWebApp().getWebRoot();
                        boolean result = IconHelper.copyMarkers(jar, webRoot, logger);
                        if (!signWatcherRegistered) {
                            Bukkit.getPluginManager().registerEvents(new SignWatcher(this), this);
                            signWatcherRegistered = true;
                        }
                        logger.info("Plugin init status: " + (result ? "ok!" : "failed!"));
                    } catch (IOException e) {
                        logger.warning("Something crashed during init: " + e.getMessage());
                    }
                }
        );
    }

    /**
     * Called when the plugin is disabled.
     * <p>
     * Delegates shutdown operations to the MarkerHelper.
     */
    @Override
    public void onDisable() {
        markerHelper.onPluginDisable();
    }

    /**
     * Returns the underlying Bukkit {@link Plugin} instance for cases where the raw plugin reference is needed.
     *
     * @return this plugin instance
     */
    @Override
    public Plugin getPlugin() {
        return this;
    }

    /**
     * Returns the path to BlueMap's webroot where static files (e.g. icons) are stored.
     * May be {@code null} if BlueMap has not initialized yet.
     *
     * @return path to BlueMap webroot or {@code null}
     */
    @Override
    public Path getWebRoot() {
        return this.webRoot;
    }

    /**
     * Returns the map of world-to-marker-set associations.
     * The map is concurrent (ConcurrentHashMap) and may be modified by multiple threads.
     *
     * @return non-null map of World -> MarkerSet
     */
    @Override
    public Map<World, MarkerSet> getMarkerSet() {
        return this.markerSet;
    }

    /**
     * Persists the marker set for the given world.
     * <p>
     * Called by the sign watcher after each marker change so the saved file tracks the live
     * state. Must run on the server thread, which is where sign events are dispatched.
     *
     * @param world the world whose marker set should be saved
     */
    @Override
    public void saveMarkerSet(World world) {
        markerHelper.saveWorldMarkerSet(world);
    }
}
