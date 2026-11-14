package it.wikinews.lucene.search;

import java.io.IOException;

import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;

import it.wikinews.lucene.index.IndexFields;

public class ArticleSearcher {
    private final SearcherManager searcherManager;

    public ArticleSearcher(SearcherManager searcherManager) {
        this.searcherManager = searcherManager;
    }

    public IndexSearcher acquireSearcher() throws IOException {
        searcherManager.maybeRefresh();
        return searcherManager.acquire();
    }

    public void releaseSearcher(IndexSearcher searcher) throws IOException {
        searcherManager.release(searcher);
    }

    public TopDocs search(IndexSearcher indexSearcher, Query query, int limit, boolean sortByDate) throws IOException {
        if (sortByDate) {
            Sort sort = new Sort(new SortField(IndexFields.DATE_MODIFIED, SortField.Type.LONG,true));

            return indexSearcher.search(query, limit, sort);
        }

        return indexSearcher.search(query, limit);
    }
    
}
