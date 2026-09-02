package com.symmetricalpalmtree.notesproutsn.extension;

// A .aidl that takes a parcelable needs an explicit import for it.
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget;
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore;
import com.symmetricalpalmtree.notesproutsn.extension.InkBundle;

/**
 * The CALENDAR point (arc 23 / Y1) -- SN's SEVENTH capability point, the fourth screen-owning one and
 * the second with paper. IScratchPad's four methods with the placement made a real type: the
 * extension owns a Month/Week/Day organizer screen (action ACTION_CALENDAR_SCREEN) the host launches
 * for a result; the host HOLDS one bind on this service for the screen's whole showing (begin ->
 * launch -> result -> end -> unbind), and every byte of ink crosses through these methods -- never
 * through the Intent. Every method: HostCallerCheck.enforce first. Timeouts are the host's.
 */
interface ICalendar {
    /** The host is about to show the screen: hold [store] for the screen's life (revoked at end()). */
    void begin(IExtensionStore store);

    /** Notebook -> calendar: one chunk of the inbound ink; [target] (the page it lands on -- a month,
     *  a week, or one half of a day) + [last] on every chunk. The extension appends chunks until
     *  last == true, then places them on the target page, minting its rows if it has none, and marks
     *  them "open selected" for the next screen launch. The only failure is
     *  IllegalStateException("store unavailable"). */
    void receiveInk(in InkBundle chunk, in CalendarTarget target, boolean last);

    /** Calendar -> notebook: after RESULT_CALENDAR_SEND the host drains the outbound ink chunk by
     *  chunk; an empty bundle (0 strokes) means done. */
    InkBundle takeOutgoing(int chunkIndex);

    /** The screen is over (result / cancel / host stop): drop the store, clear pending ink. */
    void end();
}
