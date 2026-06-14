package ie.broadsheet.app.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import org.junit.Test;

/**
 * Unit tests for the image-preview helpers in {@link TipDialog} that guard the
 * crashes fixed in this change:
 * <ul>
 * <li>divide-by-zero / oversized decode in {@code showImage()} via
 * {@link TipDialog#calculateInSampleSize(int, int, int, int)}</li>
 * <li>{@code NullPointerException} in {@code selectImage()} when the temp file
 * could not be created via {@link TipDialog#isUsableImageFile(File)}</li>
 * </ul>
 *
 * <p>
 * These cover only the pure logic; they intentionally avoid the Android view
 * and Intent machinery so they can run on a plain JVM.
 */
public class TipDialogTest {

    // ---- normal preview -----------------------------------------------------

    @Test
    public void normalPreview_downSamplesToFitTarget() {
        // 4000x3000 into a 1000x1000 view: min(4000/1000, 3000/1000) = 3.
        assertEquals(3, TipDialog.calculateInSampleSize(4000, 3000, 1000, 1000));
    }

    @Test
    public void normalPreview_neverReturnsBelowOne_whenPhotoSmallerThanTarget() {
        // 200x150 into a 1000x1000 view would give min(0, 0) = 0; must clamp to 1
        // so BitmapFactory does not treat 0 as "no sub-sampling" inconsistently.
        assertEquals(1, TipDialog.calculateInSampleSize(200, 150, 1000, 1000));
    }

    // ---- target width / height == 0 (view not laid out) ---------------------

    @Test
    public void targetWidthZero_doesNotDivideByZero_andStaysAtLeastOne() {
        int sample = TipDialog.calculateInSampleSize(4000, 3000, 0, 1000);
        assertTrue("inSampleSize must be >= 1, was " + sample, sample >= 1);
    }

    @Test
    public void targetHeightZero_doesNotDivideByZero_andStaysAtLeastOne() {
        int sample = TipDialog.calculateInSampleSize(4000, 3000, 1000, 0);
        assertTrue("inSampleSize must be >= 1, was " + sample, sample >= 1);
    }

    @Test
    public void viewNotLaidOut_bothTargetsZero_downSamplesOversizedSourceToAvoidOom() {
        // With no measured view we fall back to SAFE_TARGET_DIMENSION, so a large
        // source must still be down-sampled (> 1) rather than decoded at 1:1.
        int sample = TipDialog.calculateInSampleSize(4000, 3000, 0, 0);
        assertTrue("oversized source must be down-sampled when view unmeasured, was " + sample,
                sample > 1);
    }

    // ---- invalid image ------------------------------------------------------

    @Test
    public void invalidImage_negativeBounds_returnsOne() {
        // BitmapFactory reports -1 bounds for files it cannot decode.
        assertEquals(1, TipDialog.calculateInSampleSize(-1, -1, 1000, 1000));
    }

    @Test
    public void invalidImage_zeroBounds_returnsOne() {
        assertEquals(1, TipDialog.calculateInSampleSize(0, 0, 1000, 1000));
    }

    // ---- temp file creation failure -----------------------------------------

    @Test
    public void fileCreationFailure_nullFileIsNotUsable() {
        // createImageFile() returning null (creation failed) must abort selection.
        assertFalse(TipDialog.isUsableImageFile(null));
    }

    @Test
    public void fileCreationSuccess_realFileIsUsable() throws IOException {
        File temp = File.createTempFile("TipDialogTest", ".jpg");
        try {
            assertTrue(TipDialog.isUsableImageFile(temp));
        } finally {
            temp.delete();
        }
    }
}
