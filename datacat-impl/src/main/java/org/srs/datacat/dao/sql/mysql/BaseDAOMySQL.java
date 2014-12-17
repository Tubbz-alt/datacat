package org.srs.datacat.dao.sql.mysql;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.sql.Connection;
import java.util.concurrent.locks.ReentrantLock;
import org.srs.datacat.model.DatacatNode;
import org.srs.datacat.model.DatacatRecord;
import org.srs.datacat.model.DatasetView;
import org.srs.datacat.shared.Dataset;

/**
 *
 * @author bvan
 */
public class BaseDAOMySQL extends org.srs.datacat.dao.sql.SqlBaseDAO {

    public BaseDAOMySQL(Connection conn){
        super(conn);
    }

    public BaseDAOMySQL(Connection conn, ReentrantLock lock){
        super(conn, lock);
    }
    
    @Override
    public <T extends DatacatNode> T createNode(DatacatRecord parent, String path,
            T request) throws IOException, FileSystemException{
        if(request instanceof Dataset){
            DatasetDAOMySQL dao = new DatasetDAOMySQL(getConnection());
            return (T) dao.createDatasetNode(parent, path, (Dataset) request);
        }
        // It should be a container
        ContainerDAOMySQL dao = new ContainerDAOMySQL(getConnection());
        return (T) dao.createContainer(parent, path, request);
    }
    
    @Override
    protected String getLocationsSql(VersionParent condition, DatasetView view){
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
        String datasetSqlLocations
            = "select vd.dataset, dsv.datasetversion,  "
            + "    vdl.datasetlocation, vdl.datasetsite, vdl.path, vdl.runmin, vdl.runmax,   "
            + "    vdl.numberevents, vdl.filesizebytes, vdl.checksum, vdl.lastmodified,   "
            + "    vdl.lastscanned, vdl.scanstatus, vdl.registered,   "
            + "    CASE WHEN dsv.masterlocation = vdl.datasetlocation THEN 1 ELSE 0 END isMaster   "
            + "  FROM ("
            + "      SELECT ds.dataset, CASE WHEN ds.datasetlogicalfolder is not null "
            + "          THEN ds.datasetlogicalfolder else ds.datasetgroup END parent, "
            + "          ds.datasetname name, ds.latestversion "
            + "      FROM VerDataset ds ) vd   "
            + "  JOIN DatasetVersion dsv on (vd.latestversion = dsv.datasetversion)   "
            + "  JOIN VerDatasetLocation vdl on (dsv.datasetversion = vdl.datasetversion)  "
            + "  WHERE " + queryCondition
            + "            and " + versionString(view)
            + "  ORDER BY vd.name, dsv.versionid desc, vdl.registered";
        return datasetSqlLocations;
    }


    @Override
    protected String getChildSql(String parentClause){
        String sql = String.format(
            "SELECT objects.type, objects.pk, objects.name, objects.parent, objects.acl FROM ( "
            + "  SELECT 'F' type, datasetlogicalfolder pk, name, parent, acl "
            + "        FROM DatasetLogicalFolder "
            + "    UNION ALL "
            + "      SELECT 'G' type, datasetGroup pk, name, datasetLogicalFolder parent, acl "
            + "        FROM DatasetGroup "
            + "    UNION ALL "
            + "      SELECT 'D' type, dataset pk, datasetName name, "
            + "        CASE WHEN datasetlogicalfolder is not null "
            + "           THEN datasetlogicalfolder else datasetgroup END parent, acl "
            + "        FROM VerDataset "
            + "    ) objects "
            + "  WHERE objects.parent %s "
            + "  ORDER BY objects.name", parentClause);
        return sql;
    }
    
    @Override
    protected String getVersionMetadataSql(){
        String sql= 
            "SELECT md.datasetversion, md.type, md.metaname, md.metastring, md.metanumber FROM  "
            + " ( SELECT mn.datasetversion, 'N' type, mn.metaname, null metastring, mn.metavalue metanumber  "
            + "     FROM VerDatasetMetaNumber mn "
            + "   UNION ALL "
            + "   SELECT ms.datasetversion, 'S' type, ms.metaname, ms.metavalue metastring, null metanumber  "
            + "     FROM VerDatasetMetaString ms "
            + "  ) md "
            + "  WHERE md.datasetversion = ?";
        return sql;
    }
    
    @Override
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
            "SELECT dsv.dataset, dsv.datasetversion, dsv.versionid, dsv.datasetsource, dsv.islatest,  "
            + "     md.mdtype, md.metaname, md.metastring, md.metanumber "
            + "FROM ( "
            + "      select vd.dataset, dsv.datasetversion, dsv.versionid, dsv.datasetsource, "
            + "            CASE WHEN vd.latestversion = dsv.datasetversion THEN 1 ELSE 0 END isLatest "
            + "            FROM ("
            + "              SELECT ds.dataset, CASE WHEN ds.datasetlogicalfolder is not null "
            + "                  THEN ds.datasetlogicalfolder else ds.datasetgroup END parent, "
            + "                  ds.datasetname name, ds.latestversion "
            + "              FROM VerDataset ds) vd "
            + "            JOIN DatasetVersion dsv on (vd.latestversion = dsv.datasetversion) "
            + "            WHERE " + queryCondition
            + "                and " + versionString(view)
            + "           ORDER BY vd.name, dsv.versionid desc ) dsv "
            + " LEFT OUTER JOIN "
            + " ( SELECT mn.datasetversion, 'N' mdtype, mn.metaname, null metastring, mn.metavalue metanumber   "
            + "     FROM VerDatasetMetaNumber mn "
            + "   UNION ALL  "
            + "   SELECT ms.datasetversion, 'S' mdtype, ms.metaname, ms.metavalue metastring, null metanumber   "
            + "     FROM VerDatasetMetaString ms "
            + "  ) md on (md.datasetversion = dsv.datasetversion) ";
        return datasetSqlWithMetadata;
    }

}
