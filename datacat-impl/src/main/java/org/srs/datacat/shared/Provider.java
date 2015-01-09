
package org.srs.datacat.shared;

import java.util.HashMap;
import java.util.Map;
import org.srs.datacat.model.DatacatNode;
import org.srs.datacat.model.DatacatRecord;
import org.srs.datacat.model.DatasetModel;
import org.srs.datacat.model.ModelProvider;
import org.srs.datacat.model.container.ContainerStat;
import org.srs.datacat.model.dataset.DatasetLocationModel;
import org.srs.datacat.model.dataset.DatasetVersionModel;

/**
 *
 * @author bvan
 */
public class Provider implements ModelProvider {

    @Override
    public DatacatObject.Builder getNodeBuilder(){
        return new DatacatObject.Builder<>();
    }

    @Override
    public DatacatObject.Builder getRecordBuilder(){
        return new DatacatObject.Builder<>();
    }

    @Override
    public Dataset.Builder getDatasetBuilder(){
        return new Dataset.Builder();
    }

    @Override
    public DatasetContainerBuilder getContainerBuilder(){
        return new DatasetContainerBuilder();
    }

    @Override
    public Class<? extends ContainerStat> getStatByName(String name){
        switch(name.toLowerCase()){
            case "basic":
            case "count":
                return BasicStat.class;
            case "dataset":
                return DatasetStat.class;
            default:
                return null;
        }
    }

    @Override
    public DatasetVersionModel.Builder getVersionBuilder(){
        return new DatasetVersion.Builder();
    }

    @Override
    public Map<Class<? extends DatacatRecord>, Class<? extends DatacatRecord>> modelProviders(){
        HashMap<Class<? extends DatacatRecord>, Class<? extends DatacatRecord>> providers = new HashMap<>();
        providers.put(DatacatNode.class, DatacatObject.class);
        providers.put(DatasetModel.class, Dataset.class);
        providers.put(org.srs.datacat.model.DatasetContainer.class, DatasetContainer.class);
        providers.put(DatasetLocationModel.class, DatasetLocation.class);
        providers.put(DatasetVersionModel.class, DatasetVersion.class);
        return providers;
    }

}
