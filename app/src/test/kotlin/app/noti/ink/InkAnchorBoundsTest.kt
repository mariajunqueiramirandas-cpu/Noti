package app.noti.ink

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InkAnchorBoundsTest {
    @Test fun waitsForSizeAndRefreshesBoundsAfterReattachment() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val parent = FrameLayout(activity)
            val anchor = View(activity)
            parent.addView(anchor, FrameLayout.LayoutParams(0, 0))
            activity.setContentView(parent)
            anchor.layout(0, 0, 0, 0)
            val updates = mutableListOf<Rect>()
            val observer = InkAnchorBounds(anchor) { updates.add(it) }
            observer.start()
            assertTrue(updates.isEmpty())
            anchor.layout(0, 0, 200, 300)
            anchor.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(200, updates.last().width())
            assertEquals(300, updates.last().height())
            anchor.layout(0, 0, 300, 200)
            anchor.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(300, updates.last().width())
            assertEquals(200, updates.last().height())
            val count = updates.size
            parent.removeView(anchor)
            parent.addView(anchor)
            anchor.viewTreeObserver.dispatchOnPreDraw()
            assertTrue("A new attachment must send bounds even at the same position", updates.size > count)
            observer.stop()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun followsAncestorMovementAndTranslationWithoutAnchorLayoutChanges() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = controller.get()
            val parent = FrameLayout(activity)
            val anchor = View(activity)
            parent.addView(anchor, FrameLayout.LayoutParams(200, 300))
            activity.setContentView(parent)
            parent.layout(0, 100, 500, 700)
            anchor.layout(0, 0, 200, 300)
            var layouts = 0
            anchor.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layouts++ }
            val updates = mutableListOf<Rect>()
            val observer = InkAnchorBounds(anchor) { updates.add(it) }
            observer.start()
            val initial = updates.last()
            parent.offsetTopAndBottom(-64)
            anchor.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(0, layouts)
            assertEquals(initial.top - 64, updates.last().top)
            assertEquals(initial.height(), updates.last().height())
            parent.translationX = 40f
            anchor.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(initial.left + 40, updates.last().left)
            val count = updates.size
            anchor.viewTreeObserver.dispatchOnPreDraw()
            assertEquals("Unchanged frames should not enqueue window work", count, updates.size)
            observer.stop()
            parent.offsetTopAndBottom(100)
            anchor.viewTreeObserver.dispatchOnPreDraw()
            assertEquals(count, updates.size)
        } finally { controller.pause().stop().destroy() }
    }
}
