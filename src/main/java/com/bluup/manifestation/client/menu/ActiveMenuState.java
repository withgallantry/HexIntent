package com.bluup.manifestation.client;

import com.bluup.manifestation.common.menu.MenuPayload;
import com.bluup.manifestation.common.menu.MenuEntry;
import net.minecraft.world.InteractionHand;
import org.jetbrains.annotations.Nullable;

/**
 * Client-wide slot for "there is one Manifestation menu live right now."
 *
 * <p>Enforces the "one active menu at a time" constraint from the original
 * design doc. If a second trigger fires before the first menu is dismissed
 * or selected, the second one is ignored — no stacking.
 *
 * <p>Also tracks which {@link InteractionHand} is holding the staff, so
 * button-dispatch can route casts through the same hand. The server side
 * needs a valid hand to resolve the caster's active staff.
 *
 * <p>All access is on the render thread, so no synchronization needed.
 */
public final class ActiveMenuState {
    private static final ActiveMenuState INSTANCE = new ActiveMenuState();
    private static final long REOPEN_SUPPRESSION_MS = 750L;

    public static ActiveMenuState get() {
        return INSTANCE;
    }

    @Nullable
    private MenuPayload current;
    @Nullable
    private InteractionHand hand;
    private long suppressReopenUntilMs;
    private int suppressedMenuSignature;

    private ActiveMenuState() {
    }

    public boolean isActive() {
        return current != null;
    }

    public void open(MenuPayload menu, InteractionHand hand) {
        this.current = menu;
        this.hand = hand;
    }

    /** Idempotent — safe to call on any screen close. */
    public void clear() {
        this.current = null;
        this.hand = null;
    }

    /**
     * Clears active state and suppresses immediate reopen attempts briefly.
     *
     * <p>Used for manual menu closes so repeating-cast packets do not reopen
     * the same menu every tick.
     */
    public void clearAndSuppressReopen(MenuPayload menu) {
        clear();
        this.suppressedMenuSignature = signatureOf(menu);
        this.suppressReopenUntilMs = System.currentTimeMillis() + REOPEN_SUPPRESSION_MS;
    }

    public boolean isReopenSuppressed(MenuPayload incomingMenu) {
        if (System.currentTimeMillis() >= this.suppressReopenUntilMs) {
            return false;
        }
        return this.suppressedMenuSignature == signatureOf(incomingMenu);
    }

    private static int signatureOf(MenuPayload menu) {
        int sig = menu.title().getString().hashCode();
        sig = 31 * sig + menu.layout().ordinal();
        sig = 31 * sig + menu.theme().ordinal();
        sig = 31 * sig + menu.columns();
        sig = 31 * sig + menu.hand().ordinal();
        sig = 31 * sig + menu.entries().size();

        for (MenuEntry entry : menu.entries()) {
            sig = 31 * sig + entry.kind().ordinal();
            sig = 31 * sig + entry.label().getString().hashCode();
        }

        return sig;
    }

    @Nullable
    public MenuPayload current() {
        return current;
    }

    @Nullable
    public InteractionHand hand() {
        return hand;
    }
}
