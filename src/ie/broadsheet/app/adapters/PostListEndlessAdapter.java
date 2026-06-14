package ie.broadsheet.app.adapters;

import ie.broadsheet.app.BaseFragmentActivity;
import ie.broadsheet.app.BroadsheetApplication;
import ie.broadsheet.app.R;
import ie.broadsheet.app.model.json.Post;
import ie.broadsheet.app.model.json.PostList;
import ie.broadsheet.app.requests.PostListRequest;
import android.content.Context;
import android.util.Log;

import com.commonsware.cwac.endless.EndlessAdapter;
import com.octo.android.robospice.persistence.DurationInMillis;
import com.octo.android.robospice.persistence.exception.SpiceException;
import com.octo.android.robospice.request.listener.RequestListener;

public class PostListEndlessAdapter extends EndlessAdapter {
    public interface PostListLoadedListener {
        public void onPostListLoaded();
    }

    private PostListLoadedListener postListLoadedListener;

    private static final String TAG = "PostListEndlessAdapter";

    private boolean hasMore = true;

    private boolean loaded = false;

    private int currentPage = 0;

    private String searchTerm;

    private PostListRequest postListRequest;

    /**
     * Monotonically increasing generation counter. Every call to {@link #reset()}
     * increments this value so that in-flight requests started before the reset
     * can be identified and ignored when their callbacks fire.
     */
    private int requestGeneration = 0;

    public PostListEndlessAdapter(Context context) {
        super(context, new PostListAdapter(context), R.layout.post_list_load_more);

        setRunInBackground(false);
    }

    @Override
    protected boolean cacheInBackground() throws Exception {
        if (hasMore) {
            fetchPosts();
        }

        return hasMore;
    }

    @Override
    protected void appendCachedData() {

    }

    public boolean isLoaded() {
        return loaded;
    }

    public void setLoaded(boolean loaded) {
        this.loaded = loaded;
    }

    public String getSearchTerm() {
        return searchTerm;
    }

    public void setSearchTerm(String searchTerm) {
        this.searchTerm = searchTerm;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public int getRequestGeneration() {
        return requestGeneration;
    }

    public PostListLoadedListener getPostListLoadedListener() {
        return postListLoadedListener;
    }

    public void setPostListLoadedListener(PostListLoadedListener mListener) {
        this.postListLoadedListener = mListener;
    }

    /**
     * Reset the adapter to its initial state. Increments the request generation
     * counter so that any in-flight request callbacks that arrive after this
     * call are treated as stale and ignored.
     */
    public void reset() {
        // Bump generation so any outstanding in-flight request is isolated
        requestGeneration++;

        loaded = false;
        hasMore = true;
        searchTerm = null;
        // Reset to 0 — cacheInBackground() does NOT increment; the success
        // callback does. So the first fetch after reset requests page 1.
        currentPage = 0;

        // Release the current request (if any) so a new fetch can start
        postListRequest = null;
    }

    /**
     * Fire a request for the <em>next</em> page ({@code currentPage + 1}).
     * The page counter is only committed when the request succeeds — see
     * {@link PostListListener#onRequestSuccess(PostList)}.
     */
    public void fetchPosts() {
        if (postListRequest == null) {
            postListRequest = new PostListRequest();

            int nextPage = currentPage + 1;
            postListRequest.setPage(nextPage);
            postListRequest.setSearchTerm(searchTerm);

            // Capture the generation at the time this request is issued
            final int fetchGeneration = requestGeneration;

            BaseFragmentActivity activity = (BaseFragmentActivity) getContext();

            activity.getSpiceManager().execute(postListRequest, postListRequest.generateUrl(),
                    DurationInMillis.ONE_MINUTE, new PostListListener(fetchGeneration, nextPage));
        }
    }

    // ============================================================================================
    // INNER CLASSES
    // ============================================================================================

    public final class PostListListener implements RequestListener<PostList> {

        private final int listenerGeneration;

        private final int requestedPage;

        public PostListListener(int listenerGeneration, int requestedPage) {
            this.listenerGeneration = listenerGeneration;
            this.requestedPage = requestedPage;
        }

        /**
         * Returns {@code true} if this listener's request is still the current
         * one (i.e. no {@link PostListEndlessAdapter#reset()} happened after
         * the request was issued).
         */
        private boolean isCurrent() {
            return listenerGeneration == PostListEndlessAdapter.this.requestGeneration;
        }

        @Override
        public void onRequestFailure(SpiceException spiceException) {
            if (!isCurrent()) {
                Log.d(TAG, "Ignoring failure from stale request (gen " + listenerGeneration
                        + ", current " + PostListEndlessAdapter.this.requestGeneration + ")");
                return;
            }

            Log.d(TAG, "Failed to get results for page " + requestedPage);

            // Release the request so the user can retry
            postListRequest = null;

            // Do NOT set hasMore to false — a transient network error is not
            // "no more data". Keep hasMore true so the user can retry.
            onDataReady();

            BaseFragmentActivity activity = (BaseFragmentActivity) getContext();
            activity.showError(activity.getString(R.string.post_list_load_problem));
        }

        @Override
        public void onRequestSuccess(final PostList result) {
            if (!isCurrent()) {
                Log.d(TAG, "Ignoring success from stale request (gen " + listenerGeneration
                        + ", current " + PostListEndlessAdapter.this.requestGeneration + ")");
                return;
            }

            Log.d(TAG, "we got results for page " + requestedPage);

            loaded = true;

            // Commit the page number only now that the request succeeded
            currentPage = requestedPage;

            hasMore = (result.getCount_total() > result.getCount());

            BroadsheetApplication app = (BroadsheetApplication) PostListEndlessAdapter.this.getContext()
                    .getApplicationContext();
            if (currentPage == 1) {
                app.setPosts(null);
                ((PostListAdapter) getWrappedAdapter()).clear();
            }
            app.setPosts(result.getPosts());

            PostListAdapter adapter = (PostListAdapter) getWrappedAdapter();

            for (Post post : result.getPosts()) {
                adapter.add(post);
            }

            onDataReady();

            postListRequest = null;

            PostListEndlessAdapter.this.postListLoadedListener.onPostListLoaded();

            app.getTracker().sendView("Post List Page" + Integer.toString(currentPage));

            if (result.getCount_total() == 0) {
                BaseFragmentActivity activity = (BaseFragmentActivity) getContext();
                activity.showError(activity.getString(R.string.post_list_no_matching_posts));
            }
        }
    }
}
