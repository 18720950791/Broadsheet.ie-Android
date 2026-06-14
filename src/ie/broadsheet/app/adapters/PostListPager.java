package ie.broadsheet.app.adapters;

/**
 * Pure-Java state machine that drives endless-scroll pagination for the post list.
 *
 * <p>
 * It deliberately has <strong>no Android or networking dependencies</strong> so the
 * page/request bookkeeping can be unit tested in isolation. {@code PostListEndlessAdapter}
 * owns an instance of this class and translates its decisions into RoboSpice requests.
 * </p>
 *
 * <p>
 * The class fixes three defects that previously lived in the adapter:
 * </p>
 * <ol>
 * <li>The page number is only committed <em>after</em> the matching page loads
 * successfully. A failed attempt therefore retries the <em>same</em> page instead of
 * skipping ahead.</li>
 * <li>A failed attempt releases the in-flight flag and keeps {@link #hasMore()} {@code true},
 * so a transient network error never gets mistaken for a permanent "no more data" state and
 * the user can try again.</li>
 * <li>{@link #reset()} bumps a generation counter. Any request that was already in flight when
 * the reset happened carries the old generation, so its late-arriving result is recognised as
 * stale and ignored instead of corrupting the freshly reset state.</li>
 * </ol>
 */
public class PostListPager {

    /** Last page that was successfully loaded. {@code 0} means nothing has loaded yet. */
    private int loadedPage = 0;

    /** Whether another page may still be available. */
    private boolean hasMore = true;

    /** Whether a request is currently outstanding. */
    private boolean requestInFlight = false;

    /**
     * Generation the pager is currently on. Incremented by {@link #reset()} so that results
     * from requests started before the reset can be detected and discarded.
     */
    private int generation = 0;

    /**
     * Opaque handle for a single request attempt. It remembers which page was requested and
     * which generation it belonged to, both of which are needed to interpret the eventual
     * success/failure callback correctly.
     */
    public static final class RequestToken {
        private final int page;
        private final int generation;

        private RequestToken(int page, int generation) {
            this.page = page;
            this.generation = generation;
        }

        /** The page number this request is fetching. */
        public int getPage() {
            return page;
        }

        int getGeneration() {
            return generation;
        }
    }

    /**
     * Start a request for the next page when allowed.
     *
     * @return a token to hand back to {@link #onSuccess} / {@link #onFailure}, or {@code null}
     *         when no request should start because another one is already in flight or there
     *         are no more pages.
     */
    public RequestToken beginRequest() {
        if (requestInFlight || !hasMore) {
            return null;
        }
        requestInFlight = true;
        return new RequestToken(loadedPage + 1, generation);
    }

    /**
     * Record that the request identified by {@code token} loaded successfully.
     *
     * @param token         the token returned by {@link #beginRequest()}
     * @param moreAvailable whether the response indicates more pages exist
     * @return {@code true} if the result was applied; {@code false} if the token was stale
     *         (from before a {@link #reset()}) and therefore ignored
     */
    public boolean onSuccess(RequestToken token, boolean moreAvailable) {
        if (isStale(token)) {
            return false;
        }
        loadedPage = token.getPage();
        hasMore = moreAvailable;
        requestInFlight = false;
        return true;
    }

    /**
     * Record that the request identified by {@code token} failed.
     *
     * <p>
     * The loaded page is left untouched so a retry fetches the same page again, and
     * {@link #hasMore()} stays {@code true} so a transient failure is never treated as the end
     * of the list.
     * </p>
     *
     * @param token the token returned by {@link #beginRequest()}
     * @return {@code true} if the failure was applied; {@code false} if the token was stale and
     *         ignored
     */
    public boolean onFailure(RequestToken token) {
        if (isStale(token)) {
            return false;
        }
        requestInFlight = false;
        return true;
    }

    /**
     * Return to the initial state and invalidate any request that is still in flight. The next
     * {@link #beginRequest()} will fetch page 1, and results from requests started before this
     * call will be ignored.
     */
    public void reset() {
        loadedPage = 0;
        hasMore = true;
        requestInFlight = false;
        generation++;
    }

    /** The last page that was successfully loaded ({@code 0} when nothing has loaded yet). */
    public int getLoadedPage() {
        return loadedPage;
    }

    /** Whether another page may still be available. */
    public boolean hasMore() {
        return hasMore;
    }

    /** Whether a request is currently outstanding. */
    public boolean isRequestInFlight() {
        return requestInFlight;
    }

    private boolean isStale(RequestToken token) {
        return token == null || token.getGeneration() != generation;
    }
}
