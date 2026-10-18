package net.immortalmc.bluemap_sign_markers.watcher;

import net.immortalmc.bluemap_sign_markers.helpers.IconResolver;
import net.immortalmc.bluemap_sign_markers.helpers.MarkerService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.WritableBookMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static net.immortalmc.bluemap_sign_markers.Constants.*;

/**
 * Applies the first page of a book to a sign when the player right-clicks it.
 * <p>
 * The sign editor only shows a narrow slice of each line, which makes long icon tokens such as
 * {@code [custom1/construction.webp]} hard to type correctly. Writing the lines on a book page
 * shows them in full; this listener copies the first four lines onto the sign and creates or
 * updates the marker through {@link MarkerService}.
 * <p>
 * Anything after the fourth line is dropped, and each line is cut at 50 characters, with a chat
 * warning for each truncation. The book is not consumed. Only the main hand is handled. The
 * interaction is cancelled so the sign editor and the book screen do not open.
 */
public final class BookSignListener implements Listener {

    private static final int MAX_LINES = 4;
    private static final int MAX_LINE_LENGTH = 50;

    private final MarkerService markerService;

    /**
     * @param markerService creates and updates the marker for the clicked sign
     */
    public BookSignListener(MarkerService markerService) {
        this.markerService = markerService;
    }

    /**
     * Applies a held book to the sign that was right-clicked.
     * <p>
     * Runs at {@link EventPriority#HIGHEST} with {@code ignoreCancelled}, so a protection plugin
     * that refuses the interaction prevents this listener from writing the sign. Cancelling the
     * event ourselves stops the vanilla sign editor (or the book screen) from opening.
     *
     * @param event the interaction
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block block = event.getClickedBlock();
        if (block == null || !(block.getState() instanceof Sign sign)) return;

        ItemStack item = event.getItem();
        if (!isBook(item)) return;

        Player player = event.getPlayer();
        event.setCancelled(true);

        if (sign.isWaxed()) {
            send(player, "That sign is waxed; unwax it before applying a book");
            return;
        }

        FirstPage page = firstPage(item);
        if (page == null || page.text().isBlank()) {
            send(player, "That book has no text on its first page");
            return;
        }
        if (page.pageCount() > 1) {
            send(player, "The book has " + page.pageCount() + " pages; only the first page is used");
        }

        List<String> lines = new ArrayList<>(page.text().lines().toList());
        if (lines.size() > MAX_LINES) {
            send(player, "The book's first page has " + lines.size()
                    + " lines; using the first " + MAX_LINES);
            lines = new ArrayList<>(lines.subList(0, MAX_LINES));
        }

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.length() > MAX_LINE_LENGTH) {
                send(player, "Line " + (i + 1) + " is " + line.length()
                        + " characters; truncated to " + MAX_LINE_LENGTH);
                lines.set(i, truncate(line, MAX_LINE_LENGTH));
            }
        }
        while (lines.size() < MAX_LINES) lines.add("");

        String iconLine = lines.get(0).trim();
        if (iconLine.length() < 2 || !iconLine.startsWith("[") || !iconLine.endsWith("]")) {
            send(player, "The first line must be an icon tag, for example [set2/tree]");
            return;
        }
        String token = iconLine.substring(1, iconLine.length() - 1).toLowerCase(Locale.ROOT);

        String label1 = lines.get(1);
        String label2 = lines.get(2);
        String label3 = lines.get(3);
        if (label1.isBlank() && label2.isBlank() && label3.isBlank()) {
            send(player, "Add at least one text line after the icon");
            return;
        }

        MarkerService.Result result = markerService.createOrUpdate(
                block.getWorld(), block.getX(), block.getY(), block.getZ(),
                token, label1, label2, label3, player.getName());
        if (result == null) {
            send(player, "No marker set for this world; is BlueMap enabled?");
            return;
        }

        // Only touch the sign once the marker exists, so invalid input never changes it.
        SignSide front = sign.getSide(Side.FRONT);
        front.line(0, LegacyComponentSerializer.legacySection().deserialize(MARKER_PLACEHOLDER));
        front.line(1, Component.text(label1));
        front.line(2, Component.text(label2));
        front.line(3, Component.text(label3));
        sign.update();

        if (!token.equals(IconResolver.FALLBACK_NAME)
                && result.icon().name().equals(IconResolver.FALLBACK_NAME)) {
            send(player, "Icon '" + token + "' was not found; using " + IconResolver.FALLBACK_NAME);
        }
        String template = result.replaced() ? UPDATED_TEMPLATE : ADDED_TEMPLATE;
        send(player, String.format(template, result.icon().path(),
                block.getX(), block.getY(), block.getZ()));
    }

    private static boolean isBook(ItemStack item) {
        return item != null
                && (item.getType() == Material.WRITABLE_BOOK
                || item.getType() == Material.WRITTEN_BOOK);
    }

    /**
     * The text of the book's first page and its page count, or {@code null} when it has no pages.
     * <p>
     * Written-book pages are Adventure components and are flattened to plain text; book-and-quill
     * pages are already strings. The page's own line breaks are parsed by the caller with
     * {@link String#lines()}, which handles CR, LF and CRLF and drops a trailing empty line.
     */
    private static FirstPage firstPage(ItemStack item) {
        ItemMeta meta = item.getItemMeta();

        if (meta instanceof BookMeta book) {
            List<Component> pages = book.pages();
            if (pages.isEmpty()) return null;
            Component page = pages.get(0);
            return new FirstPage(page == null ? "" : PlainTextComponentSerializer.plainText().serialize(page),
                    pages.size());
        }
        if (meta instanceof WritableBookMeta writable) {
            List<String> pages = writable.getPages();
            if (pages.isEmpty()) return null;
            String page = pages.get(0);
            return new FirstPage(page == null ? "" : page, pages.size());
        }
        return null;
    }

    /** First-page text and how many pages the book has in total. */
    private record FirstPage(String text, int pageCount) {
    }

    /** Cuts to {@code max} UTF-16 units without splitting a surrogate pair. */
    private static String truncate(String text, int max) {
        if (text.length() <= max) return text;

        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    /**
     * Sends a message in the plugin's usual chat style.
     * <p>
     * The text is escaped first: an icon token is player input and may contain MiniMessage tags.
     */
    private static void send(Player player, String message) {
        String safe = MiniMessage.miniMessage().escapeTags(message);
        player.sendMessage(MiniMessage.miniMessage().deserialize(String.format(MSG_PREFIX, safe)));
    }
}
