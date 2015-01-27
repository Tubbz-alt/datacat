package org.srs.datacat.dao.sql;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.srs.datacat.model.DatacatNode;
import org.srs.datacat.model.DatacatRecord;
import org.srs.datacat.shared.DatasetContainerBuilder;
import org.srs.datacat.model.dataset.DatasetLocationModel;
import org.srs.datacat.model.DatasetView;
import org.srs.datacat.shared.DatacatObject;
import org.srs.datacat.shared.Dataset;
import org.srs.datacat.shared.DatasetGroup;
import org.srs.datacat.shared.DatasetLocation;
import org.srs.datacat.shared.DatasetVersion;
import org.srs.datacat.shared.LogicalFolder;
import org.srs.datacat.model.RecordType;
import org.srs.vfs.AbstractFsProvider.AfsException;
import org.srs.vfs.PathUtils;

/**
 *
 * @author bvan
 */
public class SqlBaseDAO implements org.srs.datacat.dao.BaseDAO {

    private final Connection conn;
    private final ReentrantLock lock;

    public SqlBaseDAO(Connection conn){
        this.conn = conn;
        this.lock = null;
    }

    public SqlBaseDAO(Connection conn, ReentrantLock lock){
        this.conn = conn;
        this.lock = lock;
    }

    @Override
    public void close() throws IOException{
        try {
            if(conn != null){
                conn.close();
            }
            if(lock != null && lock.isHeldByCurrentThread()){
                lock.unlock();
            }
        } catch(SQLException ex) {
            throw new IOException("Error closing data source", ex);
        }

    }

    @Override
    public void commit() throws IOException{
        try {
            conn.commit();
            if(lock != null && lock.isHeldByCurrentThread()){
                lock.unlock();
            }
        } catch(SQLException ex) {
            throw new IOException("Error committing changes", ex);
        }
    }

    protected void rollback() throws SQLException{
        if(conn != null){
            conn.rollback();
        }
        if(lock != null && lock.isHeldByCurrentThread()){
            lock.unlock();
        }
    }

    protected Connection getConnection(){
        return this.conn;
    }

    protected void delete1(String sql, Object o) throws SQLException{
        try(PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setObject(1, o);
            stmt.executeUpdate();
        }
    }

    @Override
    public DatacatNode getObjectInParent(DatacatRecord parent, String name) throws IOException, NoSuchFileException{
        return getDatacatObject(parent, name);
    }

    protected DatacatNode getDatacatObject(DatacatRecord parent, String name) throws IOException, NoSuchFileException{
        try {
            return getChild(parent, name);
        } catch(SQLException ex) {
            throw new IOException("Unknown exception occurred in the database", ex);
        }
    }

    private DatacatNode getChild(DatacatRecord parent, String name) throws SQLException, NoSuchFileException{
        String parentPath = parent != null ? parent.getPath() : null;
        String nameParam = null;

        String childPath = parent != null ? PathUtils.resolve(parent.getPath(), name) : name;
        String parentClause;
        if(parentPath == null || "/".equals(name)){
            parentClause = " is null ";
        } else {
            nameParam = name;
            parentClause = " = ? and name = ?";
        }

        String sql = getChildSql(parentClause);

        DatacatObject.Builder builder = null;
        Long pk = parent != null ? parent.getPk() : null;
        try(PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            if(nameParam != null){
                stmt.setLong(1, pk);
                stmt.setString(2, nameParam);
            }
            ResultSet rs = stmt.executeQuery();
            if(!rs.next()){
                String msg = String.format("Unable to resolve %s in parent %s", childPath, parent);
                throw new NoSuchFileException(msg);
            }
            builder = getBuilder(rs, parentPath);
        }
        completeObject(builder);
        return builder.build();
    }
    
    protected void completeObject(org.srs.datacat.shared.DatacatObject.Builder builder) throws SQLException{
        if(builder instanceof Dataset.Builder){
            completeDataset((Dataset.Builder) builder);
        } else if(builder instanceof DatasetGroup.Builder){
            completeContainer((DatasetGroup.Builder) builder,
                    "select description from DatasetGroup where datasetgroup = ?");
            setContainerMetadata(builder);
        } else if(builder instanceof LogicalFolder.Builder){
            completeContainer((LogicalFolder.Builder) builder,
                    "select description from DatasetLogicalFolder where datasetlogicalfolder = ?");
            setContainerMetadata(builder);
        }

    }

    protected void completeDataset(Dataset.Builder builder) throws SQLException{
        String sql = "select vd.datasetfileformat, "
                + "vd.datasetdatatype, vd.latestversion, "
                + "vd.registered created "
                + "from VerDataset vd "
                + "where vd.dataset = ? ";

        try(PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setLong(1, builder.pk);
            ResultSet rs = stmt.executeQuery();
            rs.next();
            builder.fileFormat(rs.getString("datasetfileformat"))
                    .dataType(rs.getString("datasetdatatype"))
                    .created(rs.getTimestamp("created"));
        }
    }

    protected void completeContainer(DatasetContainerBuilder builder, String sql) throws SQLException{
        try(PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setLong(1, builder.pk);
            ResultSet rs = stmt.executeQuery();
            rs.next();
            builder.description(rs.getString("description"));
        }
    }

    protected void setVersionMetadata(DatasetVersion.Builder builder) throws SQLException{
        String sql = getVersionMetadataSql();
        HashMap<String, Object> metadata = new HashMap<>();
        Long pk = builder.pk;
        try(PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setLong(1, pk);
            ResultSet rs = stmt.executeQuery();
            while(rs.next()){
                processMetadata(rs, metadata);
            }
        }
        if(!metadata.isEmpty()){
            builder.metadata(metadata);
        }
    }

    protected void setContainerMetadata(org.srs.datacat.shared.DatacatObject.Builder builder) throws SQLException{
        HashMap<String, Object> metadata = new HashMap<>();

        String tableType = null;
        Long pk = builder.pk;
        if(builder instanceof LogicalFolder.Builder){
            tableType = "LogicalFolder";
        } else if(builder instanceof DatasetGroup.Builder){
            tableType = "DatasetGroup";
        }
        String column = tableType;
        String mdBase = "select Metaname, Metavalue from %sMeta%s where %s = ?";
        String sql = String.format(mdBase, tableType, "String", column);
        try(PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setLong(1, pk);
            ResultSet rs = stmt.executeQuery();
            while(rs.next()){
                metadata.put(rs.getString("metaname"), rs.getString("metavalue"));
            }
        }

        sql = String.format(mdBase, tableType, "Number", column);
        try(PreparedStatement stmt = getConnection().prepareStatement(sql)) {
            stmt.setLong(1, pk);
            ResultSet rs = stmt.executeQuery();
            while(rs.next()){
                Number n;
                java.math.BigDecimal v = (java.math.BigDecimal) rs.getObject("metavalue");
                n = v.scale() == 0 ? v.toBigIntegerExact() : v;
                metadata.put(rs.getString("metaname"), (Number) n);
            }
        }
        if(!metadata.isEmpty()){
            builder.metadata(metadata);
        }
    }

    @Override
    public void delete(DatacatRecord record) throws IOException{
        if(record.getType().isContainer()){
            doDeleteDirectory(record);
        } else {
            doDeleteDataset(record);
        }
    }

    protected void doDeleteDirectory(DatacatRecord record) throws DirectoryNotEmptyException, IOException{
        if(!record.getType().isContainer()){
            String msg = "Unable to delete object: Not a Group or Folder" + record.getType();
            throw new IOException(msg);
        }
        SqlContainerDAO dao = new SqlContainerDAO(getConnection());
        // Verify directory is empty
        try(DirectoryStream ds = dao.getChildrenStream(record, DatasetView.EMPTY)) {
            if(ds.iterator().hasNext()){
                AfsException.DIRECTORY_NOT_EMPTY.throwError(record.getPath(), "Container not empty");
            }
        }
        dao.deleteContainer(record);
    }

    protected void doDeleteDataset(DatacatRecord record) throws IOException{
        if(!(record.getType() == RecordType.DATASET)){
            throw new IOException("Can only delete Datacat objects");
        }
        SqlDatasetDAO dao = new SqlDatasetDAO(getConnection());
        dao.deleteDataset(record);
    }

    @Override
    public void addMetadata(DatacatRecord record, Map metaData) throws IOException{
        try {
            switch(record.getType()){
                case DATASETVERSION:
                    addDatasetVersionMetadata(record.getPk(), metaData);
                    break;
                case GROUP:
                    addGroupMetadata(record.getPk(), metaData);
                    break;
                case FOLDER:
                    addFolderMetadata(record.getPk(), metaData);
                    break;
                default:
                    String msg = "Unable to add metadata to object type: " + record.getType();
                    throw new IOException(msg);
            }
        } catch(SQLException ex) {
            throw new IOException("Unable to add metadata to object", ex);
        }
    }

    protected void addDatasetVersionMetadata(Long pk, Map metaData) throws SQLException{
        addDatacatObjectMetadata(pk, metaData, "VerDataset", "DatasetVersion");
    }

    protected void addGroupMetadata(long datasetGroupPK, Map metaData) throws SQLException{
        addDatacatObjectMetadata(datasetGroupPK, metaData, "DatasetGroup", "DatasetGroup");
    }

    protected void addFolderMetadata(long logicalFolderPK, Map metaData) throws SQLException{
        addDatacatObjectMetadata(logicalFolderPK, metaData, "LogicalFolder", "LogicalFolder");
    }

    private void addDatacatObjectMetadata(long objectPK, Map metaData, String tablePrefix,
            String column) throws SQLException{
        if(metaData == null){
            return;
        }
        if(!(metaData instanceof HashMap)){
            metaData = new HashMap(metaData);
        }
        final String metaSql = "insert into %sMeta%s (%s,MetaName,MetaValue) values (?,?,?)";
        String metaStringSql = String.format(metaSql, tablePrefix, "String", column);
        String metaNumberSql = String.format(metaSql, tablePrefix, "Number", column);
        String metaTimestampSql = String.format(metaSql, tablePrefix, "Timestamp", column);
        PreparedStatement stmtMetaString = null;
        PreparedStatement stmtMetaNumber = null;
        PreparedStatement stmtMetaTimestamp = null;
        PreparedStatement stmt;
        
        try {
            stmtMetaString = getConnection().prepareStatement(metaStringSql);
            stmtMetaNumber = getConnection().prepareStatement(metaNumberSql);
            stmtMetaTimestamp = getConnection().prepareStatement(metaTimestampSql);
            Iterator i = metaData.entrySet().iterator();
            while(i.hasNext()){
                Map.Entry e = (Map.Entry) i.next();
                String metaName = (String) e.getKey();
                Object metaValue = e.getValue();

                // Determine MetaData Object type and insert it into the appropriate table:
                if(metaValue instanceof Timestamp){
                    stmt = stmtMetaTimestamp;
                    stmt.setTimestamp(3, (Timestamp) metaValue);
                } else if(metaValue instanceof Number){
                    stmt = stmtMetaNumber;
                    stmt.setObject(3, metaValue);
                } else { // all others stored as String
                    stmt = stmtMetaString;
                    stmt.setString(3, metaValue.toString());
                }

                stmt.setLong(1, objectPK);
                stmt.setString(2, metaName);
                stmt.executeUpdate();
            }
        } finally {
            if(stmtMetaString != null){
                stmtMetaString.close();
            }
            if(stmtMetaNumber != null){
                stmtMetaNumber.close();
            }
            if(stmtMetaTimestamp != null){
                stmtMetaTimestamp.close();
            }
        }
    }

    protected static RecordType getType(String typeChar){
        switch(typeChar){
            case "F":
                return RecordType.FOLDER;
            case "G":
                return RecordType.GROUP;
            case "D":
                return RecordType.DATASET;
            default:
                return null;
        }
    }

    protected static DatacatObject.Builder getBuilder(ResultSet rs, String parentPath) throws SQLException{
        RecordType type = getType(rs.getString("type"));
        DatacatObject.Builder o;
        switch(type){
            case DATASET:
                o = new Dataset.Builder();
                break;
            case FOLDER:
                o = new LogicalFolder.Builder();
                break;
            case GROUP:
                o = new DatasetGroup.Builder();
                break;
            default:
                o = new DatacatObject.Builder();
        }
        String name = rs.getString("name");
        o.pk(rs.getLong("pk"))
                .parentPk(rs.getLong("parent"))
                .name(name)
                .acl(rs.getString("acl"));
        if(parentPath != null && !parentPath.isEmpty()){
            o.path(PathUtils.resolve(parentPath, name));
        } else {
            o.path("/");
        }
        return o;
    }

    protected static void processMetadata(ResultSet rs, HashMap<String, Object> metadata) throws SQLException{
        String mdType = rs.getString("mdtype");
        if(mdType == null){
            return;
        }
        switch(rs.getString("mdtype")){
            case "N":
                Number n;
                java.math.BigDecimal v = (java.math.BigDecimal) rs.getObject("metanumber");
                n = v.scale() == 0 ? v.toBigIntegerExact() : v;
                metadata.put(rs.getString("metaname"), (Number) n);
                return;
            case "S":
                metadata.put(rs.getString("metaname"), rs.getString("metastring"));
            default:
        }
    }

    protected static void processLocation(ResultSet rs, Long versionPk, 
            List<DatasetLocationModel> locations) throws SQLException{
        DatasetLocation.Builder builder = new DatasetLocation.Builder();
        builder.pk(rs.getLong("datasetlocation"));
        builder.parentPk(versionPk);
        builder.site(rs.getString("datasetsite"));
        builder.resource(rs.getString("path"));
        builder.runMin(rs.getLong("runmin"));
        builder.runMax(rs.getLong("runmax"));
        builder.eventCount(rs.getLong("numberevents"));
        builder.size(rs.getLong("filesizebytes"));
        BigDecimal bd = rs.getBigDecimal("checksum");
        if(bd != null){
            builder.checksum(bd.unscaledValue().toString(16));
        }
        builder.modified(rs.getTimestamp("lastmodified"));
        builder.scanned(rs.getTimestamp("lastscanned"));
        builder.scanStatus(rs.getString("scanstatus"));
        builder.created(rs.getTimestamp("registered"));
        builder.master(rs.getBoolean("isMaster"));
        locations.add(builder.build());
    }

    @Override
    public <T extends DatacatNode> T createNode(DatacatRecord parent, String path,
            T request) throws IOException, FileSystemException{
        if(request instanceof Dataset){
            SqlDatasetDAO dao = new SqlDatasetDAO(getConnection());
            return (T) dao.createDatasetNode(parent, path, (Dataset) request);
        }
        // It should be a container
        SqlContainerDAO dao = new SqlContainerDAO(getConnection());
        return (T) dao.createContainer(parent, path, request);
    }

    protected enum VersionParent {
        DATASET,
        CONTAINER;
    }

    protected String getVersionsSql(VersionParent condition, DatasetView view){
        String queryCondition = "";
        switch(condition){
            case DATASET:
                queryCondition = "vd.dataset = ? ";
                break;
            case CONTAINER:
                queryCondition = "vd.parent = ? ";
                break;
            default:
                break;
        }

        String datasetSqlWithMetadata = 
            "WITH Dataset (dataset, parent, name, latestversion) as ("
            + "  SELECT ds.dataset, CASE WHEN ds.datasetlogicalfolder is not null "
            + "      THEN ds.datasetlogicalfolder else ds.datasetgroup END parent, "
            + "      ds.datasetname name, ds.latestversion "
            + "  FROM VerDataset ds"
            + "), "
            + "DatasetVersions (dataset, datasetversion, versionid, datasetsource, islatest) AS ( "
            + "  select vd.dataset, dsv.datasetversion, dsv.versionid, dsv.datasetsource, "
            + "        CASE WHEN vd.latestversion = dsv.datasetversion THEN 1 ELSE 0 END isLatest "
            + "        FROM Dataset vd "
            + "        JOIN DatasetVersion dsv on (vd.latestversion = dsv.datasetversion) "
            + "        WHERE " + queryCondition
            + "            and " + versionString(view)
            + "       ORDER BY vd.name, dsv.versionid desc "
            + ") "
            + "SELECT dsv.dataset, dsv.datasetversion, dsv.versionid, dsv.datasetsource, dsv.islatest,  "
            + "     md.mdtype, md.metaname, md.metastring, md.metanumber "
            + "FROM DatasetVersions dsv "
            + " JOIN "
            + " ( SELECT mn.datasetversion, 'N' mdtype, mn.metaname, null metastring, mn.metavalue metanumber   "
            + "     FROM VerDatasetMetaNumber mn "
            + "   UNION ALL  "
            + "   SELECT ms.datasetversion, 'S' mdtype, ms.metaname, ms.metavalue metastring, null metanumber   "
            + "     FROM VerDatasetMetaString ms "
            + "  ) md on (md.datasetversion = dsv.datasetversion)";
        return datasetSqlWithMetadata;
    }

    protected String getLocationsSql(VersionParent condition, DatasetView view){
        String queryCondition = "";
        switch(condition){
            case DATASET:
                queryCondition = "vd.dataset = ? ";
                break;
            case CONTAINER:
                queryCondition = "vd.datasetLogicalFolder = ? ";
                break;
            default:
                break;
        }
        String datasetSqlLocations
            = "WITH Dataset (dataset, parent, name, latestversion) as ("
            + "  SELECT ds.dataset, CASE WHEN ds.datasetlogicalfolder is not null "
            + "      THEN ds.datasetlogicalfolder else ds.datasetgroup END parent, "
            + "      ds.datasetname name, ds.latestversion "
            + "  FROM VerDataset ds "
            + ")"
            + "select vd.dataset, dsv.datasetversion,  "
            + "    vdl.datasetlocation, vdl.datasetsite, vdl.path, vdl.runmin, vdl.runmax,   "
            + "    vdl.numberevents, vdl.filesizebytes, vdl.checksum, vdl.lastmodified,   "
            + "    vdl.lastscanned, vdl.scanstatus, vdl.registered,   "
            + "    CASE WHEN dsv.masterlocation = vdl.datasetlocation THEN 1 ELSE 0 END isMaster   "
            + "  FROM Dataset vd   "
            + "  JOIN DatasetVersion dsv on (vd.latestversion = dsv.datasetversion)   "
            + "  JOIN VerDatasetLocation vdl on (dsv.datasetversion = vdl.datasetversion)  "
            + "  WHERE " + queryCondition
            + "            and " + versionString(view)
            + "  ORDER BY vd.name, dsv.versionid desc, vdl.registered";
        return datasetSqlLocations;
    }
    
    protected String getChildSql(String parentClause){
        String sql = String.format("WITH OBJECTS (type, pk, name, parent, acl) AS ( "
                + "    SELECT 'F', datasetlogicalfolder, name, parent, acl "
                + "      FROM DatasetLogicalFolder "
                + "  UNION ALL "
                + "    SELECT 'G', datasetGroup, name, datasetLogicalFolder, acl "
                + "      FROM DatasetGroup "
                + "  UNION ALL "
                + "    SELECT 'D', dataset, datasetName, "
                + "      CASE WHEN datasetlogicalfolder is not null "
                + "         THEN datasetlogicalfolder else datasetgroup END, acl "
                + "      FROM VerDataset "
                + ") "
                + "SELECT type, pk, name, parent, acl FROM OBJECTS "
                + "  WHERE parent %s "
                + "  ORDER BY name", parentClause);
        return sql;
    }

    protected String versionString(DatasetView view){
        return view.isCurrent() ? " dsv.datasetversion = vd.latestversion " : " dsv.versionid = ? ";
    }
    
    protected String getVersionMetadataSql(){
        String sql= 
                "WITH DSV (dsv) AS ( "
                + "  SELECT ? FROM DUAL "
                + ") "
                + "SELECT type, metaname, metastring, metanumber FROM  "
                + " ( SELECT 'N' mdtype, mn.metaname, null metastring, mn.metavalue metanumber  "
                + "     FROM VerDatasetMetaNumber mn where mn.DatasetVersion = (SELECT dsv FROM DSV) "
                + "   UNION ALL "
                + "   SELECT 'S' mdtype, ms.metaname, ms.metavalue metastring, null metanumber  "
                + "     FROM VerDatasetMetaString ms where ms.DatasetVersion = (SELECT dsv FROM DSV) "
                + "  )";
        return sql;
    }

}
