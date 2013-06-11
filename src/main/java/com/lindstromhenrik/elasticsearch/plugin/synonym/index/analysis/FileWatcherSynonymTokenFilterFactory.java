package com.lindstromhenrik.elasticsearch.plugin.synonym.index.analysis;


import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.synonym.SolrSynonymParser;
import org.apache.lucene.analysis.synonym.SynonymFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.synonym.WordnetSynonymParser;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Requests;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.assistedinject.Assisted;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentBuilderString;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.analysis.AbstractTokenFilterFactory;
import org.elasticsearch.index.analysis.Analysis;
import org.elasticsearch.index.analysis.AnalysisSettingsRequired;
import org.elasticsearch.index.analysis.TokenizerFactory;
import org.elasticsearch.index.analysis.TokenizerFactoryFactory;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.indices.IndexMissingException;
import org.elasticsearch.indices.TypeMissingException;
import org.elasticsearch.indices.analysis.IndicesAnalysisService;
import org.elasticsearch.rest.*;
import org.elasticsearch.rest.action.support.RestXContentBuilder;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestStatus.OK;
import static org.elasticsearch.rest.RestStatus.NOT_FOUND;
import static org.elasticsearch.rest.action.support.RestActions.splitIndices;
import static org.elasticsearch.rest.action.support.RestActions.splitTypes;
import static org.elasticsearch.rest.action.support.RestXContentBuilder.restContentBuilder;

@AnalysisSettingsRequired
public class FileWatcherSynonymTokenFilterFactory extends AbstractTokenFilterFactory {
	private final URL synonymFileURL;
	
	private final ThreadPool threadPool;
	private volatile ScheduledFuture scheduledFuture;
	private final TimeValue interval;
	
    private SynonymMap synonymMap;
    private final boolean ignoreCase;
    private final boolean expand;
    private final String format;
    private final Analyzer analyzer;
    
    private long lastModified;

    @Inject
    public FileWatcherSynonymTokenFilterFactory(Index index, @IndexSettings Settings indexSettings, Environment env, IndicesAnalysisService indicesAnalysisService, Map<String, TokenizerFactoryFactory> tokenizerFactories,
                                     @Assisted String name, @Assisted Settings settings, ThreadPool threadPool) {
        super(index, indexSettings, name, settings);

        Reader rulesReader = null;
        if (settings.get("synonyms_path") != null) {
        	String filePath = settings.get("synonyms_path", null);
        	synonymFileURL = env.resolveConfig(filePath);
        	
        	try {
            	rulesReader = new InputStreamReader(synonymFileURL.openStream(), Charsets.UTF_8);
            	lastModified = (new File(synonymFileURL.toURI())).lastModified();
            } catch (Exception e) {
                String message = String.format(Locale.ROOT, "IOException while reading synonyms_path: %s", e.getMessage());
                throw new ElasticSearchIllegalArgumentException(message);
            }
        } else {
            throw new ElasticSearchIllegalArgumentException("file watcher synonym requires `synonyms_path` to be configured");
        }
        
        this.ignoreCase = settings.getAsBoolean("ignore_case", false);
        this.expand = settings.getAsBoolean("expand", true);
        this.format = settings.get("format");
        this.threadPool = threadPool;
        this.interval = settings.getAsTime("interval", timeValueSeconds(60));
        
        String tokenizerName = settings.get("tokenizer", "whitespace");

        TokenizerFactoryFactory tokenizerFactoryFactory = tokenizerFactories.get(tokenizerName);
        if (tokenizerFactoryFactory == null) {
            tokenizerFactoryFactory = indicesAnalysisService.tokenizerFactoryFactory(tokenizerName);
        }
        if (tokenizerFactoryFactory == null) {
            throw new ElasticSearchIllegalArgumentException("failed to find tokenizer [" + tokenizerName + "] for synonym token filter");
        }
        final TokenizerFactory tokenizerFactory = tokenizerFactoryFactory.create(tokenizerName, settings);

        this.analyzer = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName, Reader reader) {
                Tokenizer tokenizer = tokenizerFactory == null ? new WhitespaceTokenizer(Lucene.ANALYZER_VERSION, reader) : tokenizerFactory.create(reader);
                TokenStream stream = ignoreCase ? new LowerCaseFilter(Lucene.ANALYZER_VERSION, tokenizer) : tokenizer;
                return new TokenStreamComponents(tokenizer, stream);
            }
        };

        try {
            SynonymMap.Builder parser = null;

            if ("wordnet".equalsIgnoreCase(settings.get("format"))) {
                parser = new WordnetSynonymParser(true, expand, analyzer);
                ((WordnetSynonymParser) parser).add(rulesReader);
            } else {
                parser = new SolrSynonymParser(true, expand, analyzer);
                ((SolrSynonymParser) parser).add(rulesReader);
            }

            synonymMap = parser.build();
        } catch (Exception e) {
            throw new ElasticSearchIllegalArgumentException("failed to build synonyms", e);
        }
        
        scheduledFuture = threadPool.scheduleWithFixedDelay(new FileMonitor(), interval);
    }

    @Override
    public TokenStream create(TokenStream tokenStream) {
        // fst is null means no synonyms
        return synonymMap.fst == null ? tokenStream : new SynonymFilter(tokenStream, synonymMap, ignoreCase);
    }
    
    public class FileMonitor implements Runnable {
    	@Override
    	public void run() {
    		try {
	    		File synonymFile = new File(synonymFileURL.toURI());
	    		if(synonymFile.exists() && lastModified < synonymFile.lastModified())
	    		{
	    			Reader rulesReader = new InputStreamReader(synonymFileURL.openStream(), Charsets.UTF_8);
	    				
    	            SynonymMap.Builder parser = null;

    	            if ("wordnet".equalsIgnoreCase(format)) {
    	                parser = new WordnetSynonymParser(true, expand, analyzer);
    	                ((WordnetSynonymParser) parser).add(rulesReader);
    	            } else {
    	                parser = new SolrSynonymParser(true, expand, analyzer);
    	                ((SolrSynonymParser) parser).add(rulesReader);
    	            }

    	            synonymMap = parser.build();
    	            lastModified = synonymFile.lastModified();
	    		}
    		} catch (Exception e) {
    			throw new RuntimeException("could not reload synonyms file: " + e.getMessage());
	        }
    	}
	}
}