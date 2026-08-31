package com.aimotion.handsfree.gesture

import android.os.SystemClock

/**
 * Learns the user's own hand shapes from ordinary use, with nothing to set up.
 *
 * ## What this replaces
 *
 * A screen that asked the user to hold each pose eight times before anything improved. That is a
 * chore in front of a feature people came to because holding a phone is difficult, and the
 * shapes it captured were the ones made while consciously posing for the camera — not the ones
 * made a week later, one-handed, on the sofa. The examples worth having are the everyday ones,
 * and they arrive for free.
 *
 * ## How it learns
 *
 * When the rule-based classifier has been sure of the same pose for several frames in a row, the
 * hand in front of the camera is very probably that pose, so the frame is recorded as an example
 * of it. [GestureTemplateStore] keeps a rolling window and matches new frames against the mean,
 * so over days the stored shape converges on how this person actually makes each gesture — and
 * because the window is rolling, it keeps following a hand whose reach changes.
 *
 * ## What it honestly cannot do
 *
 * It is bounded by the rules it learns from. Examples only arrive for poses the rule classifier
 * already fires on, so a gesture someone's hand cannot make in the way the rules expect will
 * never produce a first example, and this will never discover it. Personalising a boundary is
 * not the same as learning a new class, and no amount of daily use turns one into the other.
 *
 * Nor is there a correction signal. The app has no way to know that an action it fired was
 * unwanted -- a user who undoes something is indistinguishable, from here, from one who meant it
 * and changed their mind -- so it only ever learns from agreement, never from a mistake. That is
 * why every sample must pass the guards below rather than being trusted because it was stable.
 *
 * ## The guards
 *
 * Three, each against a specific way "learns by itself" turns into "got worse by itself":
 *
 * - **Confirmation.** A pose must hold for [stableFrames] frames, the same count that gates
 *   firing an action. A hand passing through a shape mid-movement is not an example of it.
 * - **Rate.** At most one sample per gesture per [MIN_INTERVAL_MS]. Without it a hand resting in
 *   view for a minute would fill the entire window with one frame repeated, discarding every
 *   varied example and shrinking the model to a single point.
 * - **Distance.** Once a gesture is trained, a sample far from what is already stored is
 *   discarded rather than averaged in. This is the one that matters: without it a
 *   misclassification is learned as truth, pulling the mean toward the wrong shape, which makes
 *   the next misclassification likelier -- a drift with nothing to stop it, since there is no
 *   correction signal to notice.
 */
class AdaptiveGestureLearner(private val store: GestureTemplateStore) {

    private var candidate: Gesture? = null
    private var streak = 0
    private var learnedThisHold = false
    private val lastLearnedAtMs = HashMap<Gesture, Long>()

    /**
     * Feeds one classified frame.
     *
     * @param gesture what the **rule-based** classifier made of this frame. Deliberately not the
     *   personalised match: learning from your own output is how a model talks itself into a
     *   shape that drifts further from the hand every day.
     * @param landmarks the hand this frame, or null when there wasn't one.
     * @param stableFrames how many frames of agreement to require, from the sensitivity dial —
     *   so someone who has told the app they are hard to read is also asking it to be more
     *   careful about what it learns.
     */
    fun observe(gesture: Gesture, landmarks: List<Point>?, stableFrames: Int) {
        if (landmarks == null || gesture == Gesture.UNKNOWN) {
            reset()
            return
        }

        if (gesture != candidate) {
            candidate = gesture
            streak = 1
            learnedThisHold = false
            return
        }

        streak++
        if (learnedThisHold || streak < stableFrames) return

        val now = SystemClock.elapsedRealtime()
        val last = lastLearnedAtMs[gesture]
        if (last != null && now - last < MIN_INTERVAL_MS) return

        if (!isCloseEnough(gesture, landmarks)) return

        if (store.record(gesture, landmarks)) {
            lastLearnedAtMs[gesture] = now
            // One example per hold, however long it is held: the frames of a single hold are
            // near-duplicates, and a window of duplicates is a model of one moment.
            learnedThisHold = true
        }
    }

    /** Call when the hand leaves, so the next sighting is a fresh hold rather than a continuation
     * of one that has already contributed its example. */
    fun reset() {
        candidate = null
        streak = 0
        learnedThisHold = false
    }

    /**
     * Whether a sample is near enough to what is already stored to be worth averaging in.
     *
     * An untrained gesture accepts anything — there is nothing to be near, and refusing would
     * mean never starting. The threshold is deliberately tighter than the one
     * [GestureTemplateStore] uses to *match*: being willing to recognise a shape is a smaller
     * commitment than being willing to permanently move the definition of the gesture toward it.
     */
    private fun isCloseEnough(gesture: Gesture, landmarks: List<Point>): Boolean {
        val centroid = store.centroidOf(gesture) ?: return true
        val features = normalizeLandmarks(landmarks) ?: return false
        return featureDistance(features, centroid) <= MAX_LEARN_DISTANCE
    }

    private companion object {
        const val MIN_INTERVAL_MS = 20_000L

        /** In normalised-hand units, where 1.0 is the wrist-to-middle-knuckle distance. */
        const val MAX_LEARN_DISTANCE = 1.0f
    }
}
