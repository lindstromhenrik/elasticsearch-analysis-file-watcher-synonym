package com.lindstromhenrik.elasticsearch.plugin;

import org.elasticsearch.common.inject.Module;
import org.elasticsearch.index.analysis.AnalysisModule;
import org.elasticsearch.plugins.AbstractPlugin;

import com.lindstromhenrik.elasticsearch.plugin.synonym.index.analysis.FileWatcherSynonymTokenFilterFactory;

public class FileWatcherSynonymPlugin extends AbstractPlugin {  
	   
    public String name() {  
      return "elasticsearch-file-watcher-synonym";  
    }  
    
    public String description() {
		return "Analysis-plugin for synonym";
	}
    
    @Override public void processModule(Module module) {  
      if (module instanceof AnalysisModule) {  
        ((AnalysisModule) module).addTokenFilter("file_watcher_synonym", FileWatcherSynonymTokenFilterFactory.class);  
      }  
    }  
  } 