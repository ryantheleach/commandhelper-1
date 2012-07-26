package com.laytonsmith.persistance;

import com.laytonsmith.PureUtilities.FileUtility;
import com.laytonsmith.PureUtilities.WebUtility;
import com.laytonsmith.PureUtilities.ZipReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Set;

/**
 * For data sources that can input and output strings, this class should
 * be extended.
 * @author lsmith
 */
public abstract class StringDataSource extends AbstractDataSource {
    /**
     * A reference to the data needed to output the specified data
     */
    private Object output;
    
    /**
     * A reference to the DataSourceModel used by the set and get methods.
     */
    protected DataSourceModel model;
    
    
    protected StringDataSource(URI uri) throws DataSourceException{
        super(uri);
    }
    
    /**
     * Writes the stringified data to whatever output is associated with this data source.
     * @throws IOException 
     */
    protected void writeData(String data) throws IOException, ReadOnlyException{
        if(modifiers.contains(DataSourceModifier.READONLY)){
            throw new ReadOnlyException();
        }
        process();
        if(output instanceof ZipReader){
            fileOutput((ZipReader) output, data);
        }
    }
    
    private void fileOutput(ZipReader out, String data) throws IOException, ReadOnlyException{
        if(!out.canWrite()){
            throw new ReadOnlyException();
        }
        FileUtility.write(data, out.getFile());
    }
    
    private boolean processed = false;    
    /**
     * If not already done, figures out where we need to write out this data to.
     */
    private void process(){
        if(!processed){
            if(!modifiers.contains(DataSourceModifier.HTTP) && !modifiers.contains(DataSourceModifier.HTTPS)){
                //It's a file output
                String filePath = GetFilePath(uri);
                //TODO: The relative path needs to be set properly here
                output = new ZipReader(new File(filePath));
            } else {
                //It's an HTTP output. This is not currently supported, but it should have already added the read only flag
                //for us in the top of the implementation's set function. If not, it's an Error here.
                throw new Error("HTTP/HTTPS output is currently unsupported. Did you forget to call checkSet at the top of " + this.getClass().getSimpleName() + "'s set method?");
            }
            processed = true;
        }
    }

    public void populate() throws DataSourceException {
        process();
        String data;
        try{
            if(output instanceof ZipReader){
                data = fileInput((ZipReader) output);
            } else if(output instanceof URL){
                data = urlInput((URL) output);
            } else {
                throw new UnsupportedOperationException(output.getClass().getName() + " is not a supported output type");
            }
        } catch(Exception e){
            throw new DataSourceException("Could not populate the data source with data", e);
        }
        populateModel(data);
    }
    
    private String fileInput(ZipReader source) throws IOException{
        //TODO: Check for a locking file, and block if it isn't there
        try{
            return source.getFileContents();
        } catch(FileNotFoundException e){
            if(!source.isZipped()){
                File outputFile = source.getFile();
                String contents = getBlankDataModel();
                FileUtility.write(contents, outputFile);
                return contents;
            } else {
                throw e;
            }
        }
    }
    
    private String urlInput(URL source) throws IOException{
        return WebUtility.GetPageContents(source);
    }        

    public List<String[]> keySet() {
        return model.keySet();
    }        
    
    public String get(String [] key) {
        return model.get(key);
    }  

    public boolean set(String [] key, String value) throws ReadOnlyException, IOException {
        checkSet();
        String old = get(key);
        if((old == null && value == null) || (old != null && old.equals(value))){
            return false;
        }
        model.set(key, value);
        //We need to output the model now
        writeData(serializeModel());
        return true;
    }
    
    /**
     * Given some data retrieved from who knows where, populate the 
     * @param data
     * @throws Exception 
     */
    protected abstract void populateModel(String data) throws DataSourceException;
    
    /**
     * Serializes the underlying model to a string, which can be written out to disk/network
     * @return 
     */
    protected abstract String serializeModel();
    
    /**
     * Subclasses that need a certain type of file to be the "blank" version of a data model can override this.
     * By default, an empty string is returned.
     * @return 
     */
    protected String getBlankDataModel(){
        return "";
    }
        
}