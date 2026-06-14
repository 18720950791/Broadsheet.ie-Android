package ie.broadsheet.app.adapters;

import ie.broadsheet.app.BaseFragmentActivity;
import ie.broadsheet.app.BroadsheetApplication;
import ie.broadsheet.app.model.json.Post;
import ie.broadsheet.app.model.json.PostList;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import android.app.Activity;
import android.content.Context;
import android.view.LayoutInflater;

import com.google.analytics.tracking.android.Tracker;
import com.octo.android.robospice.persistence.exception.SpiceException;
import com.octo.android.robospice.SpiceManager;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PostListEndlessAdapter}.
 *
 * Verifies the fix for the endless-scroll bug where:
 * <ul>
 *   <li>currentPage was incremented before the async request fired,</li>
 *   <li>onRequestFailure set hasMore=false and never cleared postListRequest,</li>
 *   <li>reset() set currentPage=1 but cacheInBackground() re-incremented it, and</li>
 *   <li>stale in-flight requests could corrupt state after a reset.</li>
 * </ul>
 */
public class PostListEndlessAdapterTest {

    private TestableAdapter adapter;

    @Mock
    private BaseFragmentActivity mockActivity;

    @Mock
    private BroadsheetApplication mockApp;

    @Mock
    private Tracker mockTracker;

    @Mock
    private LayoutInflater mockInflater;

    @Mock
    private SpiceManager mockSpiceManager;

    @Mock
    private PostListEndlessAdapter.PostListLoadedListener mockListener;

    // ---- Testable subclass that captures fetch state without creating a real
    //      PostListRequest (which needs BroadsheetApplication.context() at
    //      construction time). ----

    private static class TestableAdapter extends PostListEndlessAdapter {
        int lastFetchPage = -1;
        int lastFetchGeneration = -1;
        int fetchCount = 0;

        public TestableAdapter(Context context) {
            super(context);
        }

        @Override
        public void fetchPosts() {
            // Record what page and generation *would* be requested, without
            // actually creating a PostListRequest or talking to SpiceManager.
            lastFetchPage = getCurrentPage() + 1;
            lastFetchGeneration = getRequestGeneration();
            fetchCount++;
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        // Stub Activity (which is also the Context the adapter stores)
        when(mockActivity.getSystemService(Context.LAYOUT_INFLATER_SERVICE))
                .thenReturn(mockInflater);
        when(mockActivity.getApplicationContext()).thenReturn(mockApp);
        when(mockActivity.getSpiceManager()).thenReturn(mockSpiceManager);
        when(mockActivity.getString(anyInt())).thenReturn("error");

        // Stub BroadsheetApplication
        when(mockApp.getTracker()).thenReturn(mockTracker);

        adapter = new TestableAdapter(mockActivity);
        adapter.setPostListLoadedListener(mockListener);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private PostList createPostList(int count, int countTotal) {
        PostList postList = new PostList();
        postList.setCount(count);
        postList.setCount_total(countTotal);
        List<Post> posts = new ArrayList<Post>();
        for (int i = 0; i < count; i++) {
            posts.add(new Post());
        }
        postList.setPosts(posts);
        return postList;
    }

    /**
     * Use reflection to instantiate the non-static inner class
     * {@code PostListListener} so we can drive its callbacks directly.
     */
    @SuppressWarnings("unchecked")
    private PostListEndlessAdapter.PostListListener createListener(int generation, int page)
            throws Exception {
        Class<?> listenerClass = null;
        for (Class<?> c : PostListEndlessAdapter.class.getDeclaredClasses()) {
            if (c.getSimpleName().equals("PostListListener")) {
                listenerClass = c;
                break;
            }
        }
        assertNotNull("PostListListener inner class not found", listenerClass);

        // For a non-static inner class the compiler synthesises the enclosing
        // instance as the first constructor parameter.
        Constructor<?> ctor = listenerClass.getDeclaredConstructor(
                PostListEndlessAdapter.class, int.class, int.class);
        ctor.setAccessible(true);
        return (PostListEndlessAdapter.PostListListener) ctor.newInstance(
                adapter, generation, page);
    }

    // ==================================================================
    // 1. First load
    // ==================================================================

    @Test
    public void testFirstLoad_requestsPage1() throws Exception {
        assertEquals("initial currentPage", 0, adapter.getCurrentPage());

        boolean hasMore = adapter.cacheInBackground();

        assertTrue("hasMore should be true initially", hasMore);
        assertEquals("should request page 1", 1, adapter.lastFetchPage);
        assertEquals("generation should be 0", 0, adapter.lastFetchGeneration);

        // Simulate success
        PostList postList = createPostList(10, 30);
        PostListEndlessAdapter.PostListListener listener = createListener(0, 1);
        listener.onRequestSuccess(postList);

        assertEquals("currentPage should commit to 1", 1, adapter.getCurrentPage());
        assertTrue("adapter should be marked loaded", adapter.isLoaded());
    }

    // ==================================================================
    // 2. Next page
    // ==================================================================

    @Test
    public void testNextPage_requestsPage2AfterPage1Success() throws Exception {
        // Load page 1
        adapter.cacheInBackground();
        createListener(0, 1).onRequestSuccess(createPostList(10, 30));

        // Request next page
        adapter.cacheInBackground();
        assertEquals("should request page 2", 2, adapter.lastFetchPage);

        // Simulate page 2 success
        createListener(0, 2).onRequestSuccess(createPostList(10, 30));
        assertEquals("currentPage should be 2", 2, adapter.getCurrentPage());
    }

    // ==================================================================
    // 3. Failure and retry
    // ==================================================================

    @Test
    public void testFailure_hasMoreRemainsTrue() throws Exception {
        adapter.cacheInBackground();

        SpiceException exception = mock(SpiceException.class);
        createListener(0, 1).onRequestFailure(exception);

        assertTrue("hasMore must remain true after transient failure",
                adapter.isHasMore());
    }

    @Test
    public void testFailure_retryRequestsSamePage() throws Exception {
        // First attempt at page 1
        adapter.cacheInBackground();
        assertEquals(1, adapter.lastFetchPage);

        // Failure
        createListener(0, 1).onRequestFailure(mock(SpiceException.class));

        // Retry — must still request page 1 (not page 2)
        adapter.cacheInBackground();
        assertEquals("retry must request the same page", 1, adapter.lastFetchPage);
    }

    @Test
    public void testFailure_retrySucceeds_commitsPage() throws Exception {
        // First attempt at page 1 — fails
        adapter.cacheInBackground();
        createListener(0, 1).onRequestFailure(mock(SpiceException.class));

        // Retry — succeeds
        adapter.cacheInBackground();
        createListener(0, 1).onRequestSuccess(createPostList(10, 30));

        assertEquals("page should commit after successful retry", 1,
                adapter.getCurrentPage());
        assertTrue(adapter.isLoaded());
    }

    // ==================================================================
    // 4. Reset
    // ==================================================================

    @Test
    public void testReset_resetsStateToInitial() {
        adapter.reset();

        assertEquals("currentPage should be 0 after reset", 0,
                adapter.getCurrentPage());
        assertTrue("hasMore should be true after reset", adapter.isHasMore());
        assertFalse("loaded should be false after reset", adapter.isLoaded());
    }

    @Test
    public void testReset_afterPartialLoad_requestsPage1() throws Exception {
        // Load pages 1 and 2
        adapter.cacheInBackground();
        createListener(0, 1).onRequestSuccess(createPostList(10, 30));
        adapter.cacheInBackground();
        createListener(0, 2).onRequestSuccess(createPostList(10, 30));

        assertEquals(2, adapter.getCurrentPage());

        // Reset
        adapter.reset();

        // Next fetch must request page 1
        adapter.cacheInBackground();
        assertEquals("after reset, should request page 1", 1,
                adapter.lastFetchPage);
    }

    @Test
    public void testReset_incrementsGeneration() {
        assertEquals(0, adapter.getRequestGeneration());
        adapter.reset();
        assertEquals(1, adapter.getRequestGeneration());
        adapter.reset();
        assertEquals(2, adapter.getRequestGeneration());
    }

    @Test
    public void testReset_duringPendingRequest_allowsNewFetch() throws Exception {
        // Start a fetch (generation 0)
        adapter.cacheInBackground();
        assertEquals(1, adapter.lastFetchPage);

        // Reset while request is in-flight
        adapter.reset();

        // Should be able to start a new fetch (generation 1, page 1)
        adapter.cacheInBackground();
        assertEquals("post-reset fetch should request page 1", 1,
                adapter.lastFetchPage);
        assertEquals("post-reset fetch should use new generation", 1,
                adapter.lastFetchGeneration);
    }

    // ==================================================================
    // 5. Stale (late-arriving) request
    // ==================================================================

    @Test
    public void testStaleSuccess_ignoredAfterReset() throws Exception {
        // Start a fetch (generation 0, page 1)
        adapter.cacheInBackground();

        // Reset before the response arrives
        adapter.reset();

        // The old response arrives — must be ignored
        PostList staleResult = createPostList(10, 30);
        createListener(0, 1).onRequestSuccess(staleResult);

        assertEquals("currentPage must not change for stale result", 0,
                adapter.getCurrentPage());
        assertFalse("loaded must not be set by stale result",
                adapter.isLoaded());
    }

    @Test
    public void testStaleFailure_ignoredAfterReset() throws Exception {
        // Start a fetch (generation 0, page 1)
        adapter.cacheInBackground();

        // Reset
        adapter.reset();

        // The old failure arrives — must be ignored
        createListener(0, 1).onRequestFailure(mock(SpiceException.class));

        assertTrue("hasMore must remain true when stale failure arrives",
                adapter.isHasMore());
        assertEquals("generation must not be affected by stale failure", 1,
                adapter.getRequestGeneration());
    }

    @Test
    public void testStaleSuccess_doesNotAffectNewFetch() throws Exception {
        // Start fetch gen 0
        adapter.cacheInBackground();

        // Reset
        adapter.reset();

        // Start new fetch gen 1
        adapter.cacheInBackground();
        assertEquals(1, adapter.lastFetchGeneration);

        // Stale gen-0 success arrives — must be ignored
        createListener(0, 1).onRequestSuccess(createPostList(10, 30));
        assertEquals(0, adapter.getCurrentPage());

        // Gen-1 success arrives — should be applied
        createListener(1, 1).onRequestSuccess(createPostList(10, 30));
        assertEquals("current gen-1 result should be applied", 1,
                adapter.getCurrentPage());
        assertTrue(adapter.isLoaded());
    }
}
