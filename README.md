File watcher synonym for ElasticSearch
==================================

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
