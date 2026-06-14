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

    private boolean loaded = false;

    private String searchTerm;

    /** Page/request lifecycle state machine. Kept Android-free so it can be unit tested. */
    private final PostListPager pager = new PostListPager();

    public PostListEndlessAdapter(Context context) {
        super(context, new PostListAdapter(context), R.layout.post_list_load_more);

        setRunInBackground(false);
    }

    @Override
    protected boolean cacheInBackground() throws Exception {
        fetchPosts();

        // Keep the "load more" footer while more pages may exist (including while a request is
        // in flight or after a failure that can be retried).
        return pager.hasMore();
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
        return pager.getLoadedPage();
    }

    public PostListLoadedListener getPostListLoadedListener() {
        return postListLoadedListener;
    }

    public void setPostListLoadedListener(PostListLoadedListener mListener) {
        this.postListLoadedListener = mListener;
    }

    public void reset() {
        loaded = false;
        searchTerm = null;
        // Start again from page 1 and invalidate any request that is still in flight so its
        // late-arriving result cannot corrupt the reset state.
        pager.reset();
    }

    public void fetchPosts() {
        PostListPager.RequestToken token = pager.beginRequest();

        if (token == null) {
            // A request is already in flight, or there are no more pages to load.
            return;
        }

        PostListRequest postListRequest = new PostListRequest();

        postListRequest.setPage(token.getPage());
        postListRequest.setSearchTerm(searchTerm);

        BaseFragmentActivity activity = (BaseFragmentActivity) getContext();

        activity.getSpiceManager().execute(postListRequest, postListRequest.generateUrl(),
                DurationInMillis.ONE_MINUTE, new PostListListener(token));
    }

    // ============================================================================================
    // INNER CLASSES
    // ============================================================================================

    public final class PostListListener implements RequestListener<PostList> {

        private final PostListPager.RequestToken token;

        public PostListListener(PostListPager.RequestToken token) {
            this.token = token;
        }

        @Override
        public void onRequestFailure(SpiceException spiceException) {
            Log.d(TAG, "Failed to get results");

            if (!pager.onFailure(token)) {
                // Result belongs to a request that was superseded by a reset(); ignore it.
                return;
            }

            // Page number is left untouched and there may still be more pages, so the user can
            // retry the same page from the "load more" footer.
            onDataReady();

            BaseFragmentActivity activity = (BaseFragmentActivity) getContext();
            activity.showError(activity.getString(R.string.post_list_load_problem));
        }

        @Override
        public void onRequestSuccess(final PostList result) {
            Log.d(TAG, "we got results");

            boolean moreAvailable = (result.getCount_total() > result.getCount());

            if (!pager.onSuccess(token, moreAvailable)) {
                // Result belongs to a request that was superseded by a reset(); ignore it.
                return;
            }

            loaded = true;

            BroadsheetApplication app = (BroadsheetApplication) PostListEndlessAdapter.this.getContext()
                    .getApplicationContext();
            if (pager.getLoadedPage() == 1) {
                app.setPosts(null);
                ((PostListAdapter) getWrappedAdapter()).clear();
            }
            app.setPosts(result.getPosts());

            // ((PostListAdapter) getWrappedAdapter()).addAll(result.getPosts());
            PostListAdapter adapter = (PostListAdapter) getWrappedAdapter();

            for (Post post : result.getPosts()) {
                adapter.add(post);
            }

            onDataReady();

            PostListEndlessAdapter.this.postListLoadedListener.onPostListLoaded();

            app.getTracker().sendView("Post List Page" + Integer.toString(pager.getLoadedPage()));

            if (result.getCount_total() == 0) {
                BaseFragmentActivity activity = (BaseFragmentActivity) getContext();
                activity.showError(activity.getString(R.string.post_list_no_matching_posts));
            }
        }
    }
}
