package it.wikinews.lucene;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.codecs.simpletext.SimpleTextCodec;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class IndexManager {

    @Value("${wikinews.index.path}")
    private Path indexPath;
    private Directory directory;
    private Analyzer analyzer;
    private IndexWriter indexWriter;

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(indexPath);
        directory = FSDirectory.open(indexPath);
        analyzer = AnalyzerFactory.createAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setCodec(new SimpleTextCodec());
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        indexWriter = new IndexWriter(directory, config);
    }

    public IndexWriter getIndexWriter() {
        return indexWriter;
    }

    @PreDestroy
    public void close() throws IOException {
        if (indexWriter != null) {
            indexWriter.close();
        }
        if (analyzer != null) {
            analyzer.close();
        }
        if (directory != null) {
            directory.close();
        }
    }
}