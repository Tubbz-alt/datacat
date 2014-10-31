/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package org.srs.datacat.rest.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotationIntrospectorPair;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationIntrospector;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.Response;
import junit.framework.TestCase;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.model.Resource;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import org.srs.datacat.model.DatasetView;
import org.srs.datacat.rest.App;
import org.srs.datacat.shared.Dataset;
import org.srs.datacat.test.HSqlDbHarness;
import org.srs.datacat.vfs.DcPath;
import static org.srs.datacat.vfs.TestUtils.alphaMdValues;
import static org.srs.datacat.vfs.TestUtils.alphaName;
import static org.srs.datacat.vfs.TestUtils.numberMdValues;
import static org.srs.datacat.vfs.TestUtils.numberName;
import org.srs.datacat.vfs.attribute.DatasetOption;
import org.srs.rest.shared.metadata.MetadataEntry;
import org.srs.vfs.PathUtils;

/**
 *
 * @author bvan
 */
public class DatasetsResourceTest extends JerseyTest {
    
    static final ObjectMapper mdMapper = new ObjectMapper();

    static {
        AnnotationIntrospector primary = new JacksonAnnotationIntrospector();
        AnnotationIntrospector secondary = new JaxbAnnotationIntrospector(TypeFactory.defaultInstance());
        AnnotationIntrospector pair = new AnnotationIntrospectorPair(primary, secondary);
        mdMapper.setAnnotationIntrospector( pair );
    }
    
    public DatasetsResourceTest(){
    }
    
    @Override
    protected Application configure(){
        HSqlDbHarness harness = null;
        try {
            harness = new HSqlDbHarness();
        } catch(SQLException ex) {
            System.out.println(ex);

        }

        ResourceConfig app = new App(harness.getDataSource()).register(ContainerResource.class).register( PathResource.class).register(DatasetsResource.class);
        for(Resource r: app.getResources()){
            System.out.println(r.getPath());
        }
        return app;
    }


    @Test
    public void testCreateDatasetsAndViews() throws IOException{
        generateFoldersAndDatasetsAndVersions(this, 10, 100);
    }
    
    /*public static void generateFoldersAndDatasetsNodes(JerseyTest testCase, int folderCount, int datasetCount) throws IOException{
        ContainerResourceTest.generateFolders(testCase, folderCount);
        for(int i = 0; i < folderCount; i++){
            String parent =PathUtils.resolve( "/testpath",String.format("folder%05d", i));
            for(int j = 0; j < datasetCount; j++){
                String name = String.format("dataset%05d", j);
                MultivaluedHashMap<String,String> entity = new MultivaluedHashMap<>();
                entity.add( "name", name);
                entity.add( "datasetDataType",HSqlDbHarness.JUNIT_DATASET_DATATYPE);
                entity.add( "datasetSource", HSqlDbHarness.JUNIT_DATASET_DATASOURCE);
                entity.add( "datasetFileFormat", HSqlDbHarness.JUNIT_DATASET_FILEFORMAT);
                Response resp = testCase.target("/datasets" + parent)
                    .request()
                    .post( Entity.form(entity));
                TestCase.assertEquals("201",resp.getStatus());
            }
        }
    }*/
    
    public Response createOne() throws JsonProcessingException{
        String parent = "/testpath/folder00000";
        String name = "dataset0001";
        
        MultivaluedHashMap<String,String> entity = new MultivaluedHashMap<>();
        entity.add( "name", name);
        entity.add( "dataType",HSqlDbHarness.JUNIT_DATASET_DATATYPE);
        entity.add( "datasetSource", HSqlDbHarness.JUNIT_DATASET_DATASOURCE);
        entity.add( "fileFormat", HSqlDbHarness.JUNIT_DATASET_FILEFORMAT);
        entity.add( "versionId", Integer.toString(DatasetView.NEW_VER) );
        HashMap<String, Object> metadata = new HashMap<>();
        metadata.put( numberName, numberMdValues[0]);
        metadata.put( alphaName, alphaMdValues[0]);

        entity.add("versionMetadata",mdMapper.writeValueAsString(MetadataEntry.toList( metadata )));
        System.out.println("datasets" + parent + "/" + name);
        Response resp = target("/datasets.json" + parent)
                .request()
                .post(Entity.form(entity));
                
        return resp;
    }
    
    @Test
    public void testCreation() throws JsonProcessingException, IOException {
        ContainerResourceTest.generateFolders(this, 1);
        Response resp = createOne();
        TestCase.assertEquals(201, resp.getStatus());
    }
    
    @Test
    public void testCreationTwice() throws JsonProcessingException, IOException{
        ContainerResourceTest.generateFolders(this, 1);
        Response resp = createOne();
        TestCase.assertEquals(201, resp.getStatus());
        resp = createOne();
        System.out.println(resp.readEntity( String.class));
        TestCase.assertEquals(200, resp.getStatus());
    }
    
    public static void generateFoldersAndDatasetsAndVersions(JerseyTest testCase, int folderCount, int datasetCount) throws IOException{
        ContainerResourceTest.generateFolders(testCase, folderCount);
        
        for(int i = 0; i < folderCount; i++){
            String parent =PathUtils.resolve( "/testpath",String.format("folder%05d", i));
            for(int j = 0; j < datasetCount; j++){
                String name = String.format("dataset%05d", j);
                MultivaluedHashMap<String,String> entity = new MultivaluedHashMap<>();
                entity.add( "name", name);
                entity.add( "dataType",HSqlDbHarness.JUNIT_DATASET_DATATYPE);
                entity.add( "datasetSource", HSqlDbHarness.JUNIT_DATASET_DATASOURCE);
                entity.add( "fileFormat", HSqlDbHarness.JUNIT_DATASET_FILEFORMAT);
                entity.add( "versionId", Integer.toString(DatasetView.NEW_VER) );
                HashMap<String, Object> metadata = new HashMap<>();
                metadata.put( numberName, numberMdValues[i % 4]);
                metadata.put( alphaName, alphaMdValues[j % 4]);

                entity.add("versionMetadata",mdMapper.writeValueAsString(MetadataEntry.toList( metadata )));
                Response resp = testCase.target("/datasets.txt" + parent)
                    .request()
                    .post(Entity.form(entity));
                if(resp.getStatus() == 200){
                    System.out.println("duplicate: datasets" + parent + "/" + name);
                    System.out.println(resp.readEntity(String.class));
                } else {
                    TestCase.assertEquals(201, resp.getStatus());
                }
            }
        }
    }
    
    /*
    public static void generateFoldersAndDatasetsAndVersionsAndLocations(JerseyTest testCase, int folderCount, int datasetCount) throws IOException{
        ContainerResourceTest.generateFolders(testCase, folderCount);

        // Create 20k datasets
        for(int i = 0; i < folderCount; i++){
            String parent =PathUtils.resolve( "/testpath",String.format("folder%05d", i));
            for(int j = 0; j < datasetCount; j++){
                String name = String.format("dataset%05d", j);
                MultivaluedHashMap<String,String> entity = new MultivaluedHashMap<>();
                entity.add( "name", name);
                entity.add( "dataType",HSqlDbHarness.JUNIT_DATASET_DATATYPE);
                entity.add( "datasetSource", HSqlDbHarness.JUNIT_DATASET_DATASOURCE);
                entity.add( "fileFormat", HSqlDbHarness.JUNIT_DATASET_FILEFORMAT);
                entity.add( "versionId", Integer.toString(DatasetView.NEW_VER) );
                HashMap<String, Object> metadata = new HashMap<>();
                metadata.put( numberName, numberMdValues[i % 4]);
                metadata.put( alphaName, alphaMdValues[j % 4]);

                System.out.println(mdMapper.writeValueAsString(MetadataEntry.toList( metadata )));
                entity.add("versionMetadata",mdMapper.writeValueAsString(mdMapper.writeValueAsString(MetadataEntry.toList( metadata ))));
                Response resp = testCase.target("/datasets" + parent)
                    .request()
                    .post( Entity.form(entity));
                TestCase.assertEquals( "201",resp.getStatus());
            }
        }
    }
    */
    
    
}
