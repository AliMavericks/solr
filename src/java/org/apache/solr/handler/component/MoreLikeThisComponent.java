/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.handler.component;

import java.io.IOException;
import java.net.URL;

import org.apache.lucene.queryParser.ParseException;
import org.apache.solr.common.params.MoreLikeThisParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.MoreLikeThisHandler;
import org.apache.solr.handler.SearchHandler;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.request.SolrQueryResponse;
import org.apache.solr.search.DocList;
import org.apache.solr.search.SolrIndexSearcher;

/**
 * TODO!
 * 
 * @version $Id$
 * @since solr 1.3
 */
public class MoreLikeThisComponent extends SearchComponent
{
  @Override
  public void prepare(SolrQueryRequest req, SolrQueryResponse rsp) throws IOException, ParseException 
  {
    
  }

  @Override
  public void process(SolrQueryRequest req, SolrQueryResponse rsp) throws IOException 
  {
    SolrParams p = req.getParams();
    if( p.getBool( MoreLikeThisParams.MLT, false ) ) {
      ResponseBuilder builder = SearchHandler.getResponseBuilder( req );
      SolrIndexSearcher searcher = req.getSearcher();
      
      MoreLikeThisHandler.MoreLikeThisHelper mlt 
        = new MoreLikeThisHandler.MoreLikeThisHelper( p, searcher );
      
      int mltcount = p.getInt( MoreLikeThisParams.DOC_COUNT, 5 );
      NamedList<DocList> sim = mlt.getMoreLikeThese(
          builder.getResults().docList, mltcount, builder.getFieldFlags() );

      // TODO ???? add this directly to the response?
      rsp.add( "moreLikeThis", sim );
    }
  }

  /////////////////////////////////////////////
  ///  SolrInfoMBean
  ////////////////////////////////////////////

  @Override
  public String getDescription() {
    return "More Like This";
  }

  @Override
  public String getVersion() {
    return "$Revision$";
  }

  @Override
  public String getSourceId() {
    return "$Id$";
  }

  @Override
  public String getSource() {
    return "$URL$";
  }

  @Override
  public URL[] getDocs() {
    return null;
  }
}