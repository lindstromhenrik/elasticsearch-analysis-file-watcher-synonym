File watcher synonym for ElasticSearch
======================================

The file watcher synonym plugin adds a synonym token filter that reloads the synonym file at given intervals (default 60s).

Example:

	{
	    "index" : {
	        "analysis" : {
	            "analyzer" : {
	                "synonym" : {
	                    "tokenizer" : "whitespace",
	                    "filter" : ["synonym"]
 	               }
	            },
	            "filter" : {
	                "synonym" : {
	                    "type" : "file_watcher_synonym",
	                    "synonyms_path" : "analysis/synonym.txt"
	                    "interval" : "10s"
	                }
	            }
	        }
	    }
	}

## Installation

Using the plugin command (inside your elasticsearch/bin directory) the plugin can be installed by:
```
bin/plugin -install analysis-file-watcher-synonym  -url https://github.com/lindstromhenrik/elasticsearch-analysis-file-watcher-synonym/releases/download/v0.90.9-0.1.0/elasticsearch-file-watcher-synonym-0.90.9-0.1.0.zip
```

### Compatibility


**Note**: Please make sure the plugin version matches with your elasticsearch version. Follow this compatibility matrix

    ------------------------------------------------------
    | analysis file watcher synonym   | Elasticsearch    |
    ------------------------------------------------------
    | 0.90.9-0.1.0                    | 0.90.9 -> master |
    ------------------------------------------------------