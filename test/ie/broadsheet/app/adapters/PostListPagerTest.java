package ie.broadsheet.app.adapters;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import ie.broadsheet.app.adapters.PostListPager.RequestToken;

/**
 * Unit tests for {@link PostListPager}, the Android-free state machine behind the post list's
 * endless scrolling. Covers the five behaviours called out in the bug report: first load, loading
 * the next page, retrying after a failure, resetting, and a stale result arriving after a reset.
 */
public class PostListPagerTest {

    private PostListPager pager;

    @Before
    public void setUp() {
        pager = new PostListPager();
    }

    // --------------------------------------------------------------------------------------------
    // 1. First load
    // --------------------------------------------------------------------------------------------

    @Test
    public void firstLoadRequestsPageOneAndCommitsOnSuccess() {
        assertEquals(0, pager.getLoadedPage());
        assertTrue(pager.hasMore());
        assertFalse(pager.isRequestInFlight());

        RequestToken token = pager.beginRequest();

        assertNotNull(token);
        assertEquals("first request must fetch page 1", 1, token.getPage());
        assertTrue("a request is now outstanding", pager.isRequestInFlight());
        // Page is NOT committed until the response arrives.
        assertEquals(0, pager.getLoadedPage());

        boolean applied = pager.onSuccess(token, true);

        assertTrue(applied);
        assertEquals("page 1 committed only after success", 1, pager.getLoadedPage());
        assertTrue(pager.hasMore());
        assertFalse(pager.isRequestInFlight());
    }

    @Test
    public void secondConcurrentRequestIsRefusedWhileOneIsInFlight() {
        RequestToken first = pager.beginRequest();
        assertNotNull(first);

        // Nothing should start a parallel request for the same page.
        assertNull(pager.beginRequest());
    }

    // --------------------------------------------------------------------------------------------
    // 2. Next page
    // --------------------------------------------------------------------------------------------

    @Test
    public void nextPageRequestsPageTwoAfterPageOneLoaded() {
        RequestToken page1 = pager.beginRequest();
        pager.onSuccess(page1, true);

        RequestToken page2 = pager.beginRequest();

        assertNotNull(page2);
        assertEquals(2, page2.getPage());

        pager.onSuccess(page2, true);
        assertEquals(2, pager.getLoadedPage());
    }

    @Test
    public void noMorePagesStopsFurtherRequests() {
        RequestToken page1 = pager.beginRequest();
        // Server says there is nothing beyond this page.
        pager.onSuccess(page1, false);

        assertFalse(pager.hasMore());
        assertNull("must not request another page when none remain", pager.beginRequest());
    }

    // --------------------------------------------------------------------------------------------
    // 3. Failed retry
    // --------------------------------------------------------------------------------------------

    @Test
    public void failureRetriesTheSamePageAndIsNotTreatedAsEndOfList() {
        RequestToken page1 = pager.beginRequest();
        pager.onSuccess(page1, true);

        RequestToken page2Attempt = pager.beginRequest();
        assertEquals(2, page2Attempt.getPage());

        boolean applied = pager.onFailure(page2Attempt);

        assertTrue(applied);
        assertFalse("failure releases the in-flight request", pager.isRequestInFlight());
        assertTrue("a transient failure must NOT look like end-of-list", pager.hasMore());
        assertEquals("page is not advanced on failure", 1, pager.getLoadedPage());

        // The retry must request the very same page that just failed.
        RequestToken retry = pager.beginRequest();
        assertNotNull(retry);
        assertEquals("retry fetches the same page", 2, retry.getPage());

        pager.onSuccess(retry, true);
        assertEquals(2, pager.getLoadedPage());
    }

    @Test
    public void failureOnFirstPageAllowsReloadingFromPageOne() {
        RequestToken page1 = pager.beginRequest();
        pager.onFailure(page1);

        RequestToken retry = pager.beginRequest();
        assertEquals(1, retry.getPage());
    }

    // --------------------------------------------------------------------------------------------
    // 4. Reset
    // --------------------------------------------------------------------------------------------

    @Test
    public void resetStartsAgainFromPageOne() {
        pager.onSuccess(pager.beginRequest(), true); // page 1
        pager.onSuccess(pager.beginRequest(), true); // page 2
        assertEquals(2, pager.getLoadedPage());

        pager.reset();

        assertEquals(0, pager.getLoadedPage());
        assertTrue(pager.hasMore());
        assertFalse(pager.isRequestInFlight());

        RequestToken afterReset = pager.beginRequest();
        assertEquals("after reset the next request is page 1 again", 1, afterReset.getPage());

        pager.onSuccess(afterReset, true);
        assertEquals(1, pager.getLoadedPage());
    }

    @Test
    public void resetRestoresHasMoreAfterEndOfListWasReached() {
        pager.onSuccess(pager.beginRequest(), false); // page 1, no more pages
        assertFalse(pager.hasMore());

        pager.reset();

        assertTrue("reset must clear a previous end-of-list state", pager.hasMore());
        assertNotNull(pager.beginRequest());
    }

    // --------------------------------------------------------------------------------------------
    // 5. Old request arriving late (after a reset)
    // --------------------------------------------------------------------------------------------

    @Test
    public void lateSuccessFromBeforeResetIsIgnored() {
        RequestToken stale = pager.beginRequest(); // page 1, generation 0
        assertEquals(1, stale.getPage());

        pager.reset();
        RequestToken fresh = pager.beginRequest(); // page 1, generation 1
        assertTrue(pager.isRequestInFlight());

        // The old in-flight request finally comes back AFTER the reset.
        boolean applied = pager.onSuccess(stale, true);

        assertFalse("stale success must be ignored", applied);
        assertEquals("stale success must not commit a page", 0, pager.getLoadedPage());
        assertTrue("the fresh request must remain in flight", pager.isRequestInFlight());

        // The fresh request still completes normally.
        assertTrue(pager.onSuccess(fresh, true));
        assertEquals(1, pager.getLoadedPage());
    }

    @Test
    public void lateFailureFromBeforeResetIsIgnored() {
        RequestToken stale = pager.beginRequest(); // generation 0

        pager.reset();
        RequestToken fresh = pager.beginRequest(); // generation 1
        assertTrue(pager.isRequestInFlight());

        boolean applied = pager.onFailure(stale);

        assertFalse("stale failure must be ignored", applied);
        assertTrue("stale failure must not release the fresh request", pager.isRequestInFlight());
        assertTrue(pager.hasMore());

        // Fresh request can still succeed afterwards.
        assertTrue(pager.onSuccess(fresh, true));
        assertEquals(1, pager.getLoadedPage());
    }

    @Test
    public void nullTokenIsHandledGracefully() {
        assertFalse(pager.onSuccess(null, true));
        assertFalse(pager.onFailure(null));
    }
}
