
package org.srs.datacat.vfs.attribute;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import org.srs.datacat.model.DatasetView;
import org.srs.datacat.model.HasDatasetViewInfo;
import org.srs.datacat.model.RequestView;
import org.srs.datacat.shared.Dataset;
import org.srs.datacat.shared.DatasetLocation;
import org.srs.datacat.shared.DatasetVersion;
import org.srs.datacat.shared.dataset.DatasetViewInfo;
import org.srs.datacat.shared.dataset.FullDataset;
import org.srs.datacat.vfs.DcFile;
import org.srs.datacat.vfs.DcFileSystemProvider;

/**
 *
 * @author bvan
 */
public class DatasetViewProvider implements DcViewProvider<RequestView> {

    private final DcFile file;
    private final DcFileSystemProvider provider;
    private boolean allVersionsLoaded = false;
    
    private final HashMap<Integer, DatasetViewInfo> versionCache = new HashMap<>(4);
    
    public DatasetViewProvider(DcFile file, Dataset object){
        this.file = file;
        this.provider = file.getPath().getFileSystem().provider();
        if(object instanceof FullDataset){
            DatasetViewInfo viewInfo = ((HasDatasetViewInfo) object).getDatasetViewInfo();
            
            if(viewInfo.getVersion().isLatest()){
                versionCache.put( DatasetView.CURRENT_VER, viewInfo);
            }
            versionCache.put( viewInfo.getVersion().getVersionId(), viewInfo);
        }
    }
    
    public void clear(){
        synchronized(this){
            versionCache.clear();
            //locationCache.clear();
        }
    }

    @Override
    public Dataset withView(RequestView requestView) throws FileNotFoundException, IOException {
        return withView(requestView.getDatasetView(DatasetView.MASTER), requestView.includeMetadata());
    }
    
    public Dataset withView(DatasetView view, boolean withMetadata) throws FileNotFoundException, IOException {
        if(view == DatasetView.EMPTY){
            return (Dataset) file.getObject();
        }
        DatasetViewInfo dsv;
        DatasetVersion retDsv;
        Set<DatasetLocation> retLocations;
        synchronized(this) {
            if(!versionCache.containsKey(view.getVersionId())){
                dsv = provider.getDatasetViewInfo(file, view);
                if(dsv.getVersion().isLatest()){
                    versionCache.put(DatasetView.CURRENT_VER, dsv);
                }
                versionCache.put(dsv.getVersion().getVersionId(), dsv);
            }
            dsv = versionCache.get(view.getVersionId());
        }
        if(dsv == null){
            String msg = "Unable to process view. Version %d not found";
            throw new FileNotFoundException( String.format( msg, view.getVersionId() ) );
        }
        retDsv = dsv.getVersion();
        retLocations = dsv.getLocations();
        if(retLocations == null && !(view.zeroSites() || view.zeroOrMoreSites())){
            String msg = "No locations found for dataset version %d";
            throw new FileNotFoundException(String.format( msg, view.getVersionId()));
        }
        Dataset.Builder b = new Dataset.Builder((Dataset) file.getObject());
        if(!withMetadata){ // mask metadata
            retDsv = new DatasetVersion.Builder(retDsv).metadata((List)null).build();
        }
        b.version(retDsv);
        
        if(!view.zeroSites()){                                 // Don't bother if zeroSites is true
            if(view.allSites() && !retLocations.isEmpty()){      // We want all sites 
                b.locations(retLocations);                         // retLocations is not null/empty
            } else {
                if(dsv.getLocation(view.getSite()) != null){     // If we find a site, use it
                    b.location(dsv.getLocation(view.getSite()));
                } else if(!view.zeroOrMoreSites()){              // No site, is zero acceptable?
                    String msg = "Location %s not found";
                    throw new FileNotFoundException(String.format(msg, view.getSite()));
                }
            }
        }
        return b.build();
    }

    @Override
    public String name(){
        return "dsviews";
    }

}
