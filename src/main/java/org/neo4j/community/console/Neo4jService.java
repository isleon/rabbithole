package org.neo4j.community.console;

import org.neo4j.geoff.except.SubgraphError;
import org.neo4j.geoff.except.SyntaxError;
import org.neo4j.graphdb.*;
import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.kernel.lifecycle.LifecycleException;
import org.neo4j.test.TestGraphDatabaseFactory;
import org.slf4j.Logger;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

import static org.neo4j.helpers.collection.MapUtil.map;

/**
* @author mh
* @since 08.04.12
*/
class Neo4jService {

    private static final Logger LOG = org.slf4j.LoggerFactory.getLogger(Neo4jService.class);
    public static final String VERSION_REGEXP = "(\\d+\\.\\d+(?:\\.experimental|-cost|-rule)?)";

    private GraphDatabaseService gdb;

    private Index index;
    private CypherQueryExecutor cypherQueryExecutor;
    private GeoffImportService geoffService;
    private GeoffExportService geoffExportService;
    private CypherExportService cypherExportService;
    private String version;
    private boolean initialized;

    Neo4jService() throws Throwable {
        this(createInMemoryDatabase(),true);
    }

    private static GraphDatabaseService createInMemoryDatabase() throws Throwable {
        try {
            Map<String,String> config = MapUtil.stringMap("execution_guard_enabled", "true","mapped_memory_total_size","5M","dbms.pagecache.memory","5M","keep_logical_logs","false","cache_type","none","query_cache_size","15");
            return new TestGraphDatabaseFactory().newImpermanentDatabaseBuilder().setConfig(config).newGraphDatabase();
        } catch(Throwable re) {
            Throwable t=re.getCause();
            if (re instanceof LifecycleException || t instanceof LifecycleException || t instanceof Error || re instanceof Error) {
                re.printStackTrace();
                Halt.halt("Lifecycle Exception during creation of database "+re.getMessage());
            }
            if (t instanceof RuntimeException) throw (RuntimeException)t;
            if (t instanceof Error) throw (Error)t;
            throw t;
        }
    }

    Neo4jService(GraphDatabaseService gdb) throws Throwable {
        this(gdb,false);
    }

    private Neo4jService(GraphDatabaseService gdb, boolean ownsDatabase) {
        if (gdb == null) throw new IllegalArgumentException("Graph Database must not be null");
        this.gdb = gdb;
        this.ownsDatabase = ownsDatabase;
        index = new Index(this.gdb);
        cypherQueryExecutor = new CypherQueryExecutor(gdb,index);
        geoffService = new GeoffImportService(gdb, index);
        geoffExportService = new GeoffExportService(gdb);
        cypherExportService = new CypherExportService(gdb);
    }

    public Map cypherQueryViz(String query) {
        final boolean invalidQuery = query == null || query.trim().isEmpty() || cypherQueryExecutor.isMutatingQuery(query);
        return invalidQuery ? cypherQueryViz((CypherQueryExecutor.CypherResult) null) : cypherQueryViz(cypherQuery(query, null));
    }
    public Map cypherQueryViz(CypherQueryExecutor.CypherResult result) {
        try (Transaction tx = gdb.beginTx()) {
            final SubGraph subGraph = SubGraph.from(gdb).markSelection(result);
            Map<String, Object> viz = map("nodes", subGraph.getNodes().values(), "links", subGraph.getRelationshipsWithIndexedEnds().values());
            tx.success();
            return viz;
        }
    }

    public String exportToGeoff() {
        try (Transaction tx = gdb.beginTx()) {
            String result = geoffExportService.export();
            tx.success();
            return result;
        }
    }
    
    public String exportToCypher() {
        try (Transaction tx = gdb.beginTx()) {
            String cypher = cypherExportService.export();
            tx.success();
            return cypher;
        }
    }

    public Map<String,Object> mergeGeoff(String geoff) {
        try (Transaction tx = gdb.beginTx()) {
            final Map<String,Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, PropertyContainer> entry : geoffService.mergeGeoff(geoff).entrySet()) {
                result.put(entry.getKey(),geoffExportService.toMap(entry.getValue()));
            }
            tx.success();
            return result;
        } catch (SubgraphError subgraphError) {
            throw new RuntimeException("Error merging:\n"+geoff,subgraphError);
        } catch (SyntaxError syntaxError) {
            throw new RuntimeException("Syntax error merging:\n"+geoff,syntaxError);
        }
    }

    public Collection<Map<String,Object>> cypherQueryResults(String query) {
        Collection<Map<String,Object>> result=new ArrayList<>();
        for (Map<String, Object> row : cypherQuery(query, null)) {
            result.add(row);
        }
        return result;
    }

    public CypherQueryExecutor.CypherResult initCypherQuery(String query, Map<String, Object> queryParams) {
        return cypherQueryExecutor.cypherQuery(query,null,queryParams);
    }
    public CypherQueryExecutor.CypherResult cypherQuery(String query, Map<String, Object> queryParams) {
        return cypherQueryExecutor.cypherQuery(query,version,queryParams);
    }

    public String prettify(String query) {
        return cypherQueryExecutor.prettify(query);
    }

    public void stop() {
        if (gdb!=null) {
            LOG.warn("Shutting down service "+this);
            if (ownsDatabase) gdb.shutdown();
            index = null;
            cypherQueryExecutor=null;
            geoffExportService =null;
            cypherExportService =null;
            geoffService =null;
            gdb=null;
            System.gc();
        }
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        if (version==null || version.trim().isEmpty()) this.version=null;
        else {
            this.version = checkVersion(version);
        }
    }

    public static String checkVersion(String version) {
        version = version.replaceAll("^"+VERSION_REGEXP+".*","$1");
        if (!version.matches(VERSION_REGEXP)) throw new IllegalArgumentException("Incorrect version string "+version);
        return version;
    }

    public boolean isMutatingQuery(String query) {
        return cypherQueryExecutor.isMutatingQuery(query);
    }
    public boolean isCypherQuery(String query) {
        return cypherQueryExecutor.isCypherQuery(query);
    }

    public GraphDatabaseService getGraphDatabase() {
        return gdb;
    }

    public void importGraph(SubGraph graph) {
        try (Transaction tx = gdb.beginTx()) {
            graph.importTo(gdb);
            tx.success();
        }
    }

    public URL toUrl(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private final boolean ownsDatabase;
    public boolean doesOwnDatabase() {
        return ownsDatabase;
    }

    public Neo4jService initializeFrom(SubGraph graph) {
        importGraph(graph);
        setInitialized();
        return this;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized() {
        this.initialized = true;
    }

    public Map exportToJson(Map<String, Object> graph) {
        Map<String,Map<String,Object>> result=new HashMap<>(graph.size());
        for (Map.Entry<String, Object> entry : graph.entrySet()) {
            Map<String, Object> data = null;
            if (entry.getValue() instanceof Map) {
                //noinspection unchecked
                data = (Map<String, Object>) entry.getValue();
            }
            if (entry.getValue() instanceof PropertyContainer) {
                final PropertyContainer value = (PropertyContainer) entry.getValue();
                if (value instanceof Node) data=SubGraph.toMap((Node)value);
                if (value instanceof Relationship) data=SubGraph.toMap((Relationship)value);
            }
            if (data!=null) result.put(entry.getKey(),data);
        }
        return result;
    }
}
