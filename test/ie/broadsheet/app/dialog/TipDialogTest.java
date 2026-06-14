package ie.broadsheet.app.dialog;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link TipDialog#calculateInSampleSize(int, int, int, int)}.
 *
 * Covers:
 * <ul>
 *   <li>target width or height == 0  (view not laid out)</li>
 *   <li>invalid / corrupt image (photoW or photoH &le; 0)</li>
 *   <li>normal preview with valid dimensions</li>
 *   <li>file-creation-failure path (selectImage aborts – structural guard)</li>
 * </ul>
 */
public class TipDialogTest {

    // ------------------------------------------------------------------ //
    //  target width / height == 0  (view not yet laid out)               //
    // ------------------------------------------------------------------ //

    @Test
    public void calculateInSampleSize_targetWidthZero_returnsOne() {
        int result = TipDialog.calculateInSampleSize(2000, 1500, 0, 800);
        assertEquals("Must return 1 when targetW is 0", 1, result);
    }

    @Test
    public void calculateInSampleSize_targetHeightZero_returnsOne() {
        int result = TipDialog.calculateInSampleSize(2000, 1500, 800, 0);
        assertEquals("Must return 1 when targetH is 0", 1, result);
    }

    @Test
    public void calculateInSampleSize_bothTargetZero_returnsOne() {
        int result = TipDialog.calculateInSampleSize(2000, 1500, 0, 0);
        assertEquals("Must return 1 when both target dimensions are 0", 1, result);
    }

    // ------------------------------------------------------------------ //
    //  invalid / corrupt image (photoW or photoH <= 0)                   //
    // ------------------------------------------------------------------ //

    @Test
    public void calculateInSampleSize_photoWidthNegative_returnsOne() {
        // BitmapFactory sets outWidth = -1 when it cannot decode
        int result = TipDialog.calculateInSampleSize(-1, 1500, 800, 600);
        assertEquals("Must return 1 when photoW is negative (corrupt image)", 1, result);
    }

    @Test
    public void calculateInSampleSize_photoHeightZero_returnsOne() {
        int result = TipDialog.calculateInSampleSize(2000, 0, 800, 600);
        assertEquals("Must return 1 when photoH is 0 (invalid image)", 1, result);
    }

    @Test
    public void calculateInSampleSize_bothPhotoDimsNegative_returnsOne() {
        int result = TipDialog.calculateInSampleSize(-1, -1, 800, 600);
        assertEquals("Must return 1 when both photo dims are invalid", 1, result);
    }

    // ------------------------------------------------------------------ //
    //  normal preview – valid dimensions                                  //
    // ------------------------------------------------------------------ //

    @Test
    public void calculateInSampleSize_normalImage_returnsCorrectScale() {
        // 2000/800 = 2, 1500/600 = 2  -> min(2,2) = 2
        int result = TipDialog.calculateInSampleSize(2000, 1500, 800, 600);
        assertEquals("Should compute min(photoW/tW, photoH/tH)", 2, result);
    }

    @Test
    public void calculateInSampleSize_landscapePhoto_returnsMinScale() {
        // 4000/1000 = 4, 2000/500 = 4  -> min = 4
        int result = TipDialog.calculateInSampleSize(4000, 2000, 1000, 500);
        assertEquals(4, result);
    }

    @Test
    public void calculateInSampleSize_asymmetricScale_returnsMin() {
        // 3000/500 = 6, 1000/800 = 1  -> min(6,1) = 1
        int result = TipDialog.calculateInSampleSize(3000, 1000, 500, 800);
        assertEquals("Should use the smaller scale factor", 1, result);
    }

    @Test
    public void calculateInSampleSize_photoSmallerThanTarget_returnsOne() {
        // 200/400 = 0, 100/300 = 0  -> min(0,0) = 0 -> max(0,1) = 1
        int result = TipDialog.calculateInSampleSize(200, 100, 400, 300);
        assertEquals("Must never return less than 1", 1, result);
    }

    @Test
    public void calculateInSampleSize_exactFit_returnsOne() {
        // 800/800 = 1, 600/600 = 1  -> min(1,1) = 1
        int result = TipDialog.calculateInSampleSize(800, 600, 800, 600);
        assertEquals(1, result);
    }

    @Test
    public void calculateInSampleSize_resultAlwaysPositive() {
        // Stress: very large photo, very small target
        int result = TipDialog.calculateInSampleSize(10000, 10000, 1, 1);
        assertTrue("inSampleSize must be >= 1", result >= 1);
        assertEquals(10000, result);
    }

    // ------------------------------------------------------------------ //
    //  file-creation-failure structural guard                             //
    // ------------------------------------------------------------------ //

    @Test
    public void maxFallbackDimension_isReasonable() {
        // Sanity-check the safety cap constant so future refactors don't
        // accidentally set it to e.g. 100 000 and cause OOM.
        assertTrue("MAX_FALLBACK_DIMENSION should be > 0",
                TipDialog.MAX_FALLBACK_DIMENSION > 0);
        assertTrue("MAX_FALLBACK_DIMENSION should be <= 2048 to avoid OOM",
                TipDialog.MAX_FALLBACK_DIMENSION <= 2048);
    }

    @Test
    public void calculateInSampleSize_allZeros_returnsOne() {
        // All-zero edge case: both photo and target dims are 0
        int result = TipDialog.calculateInSampleSize(0, 0, 0, 0);
        assertEquals("All-zero dims must return 1", 1, result);
    }

    @Test
    public void calculateInSampleSize_negativeTarget_returnsOne() {
        // Negative target dimension should never occur, but be safe
        int result = TipDialog.calculateInSampleSize(2000, 1500, -100, 600);
        assertEquals("Negative target dim must return 1", 1, result);
    }
}
