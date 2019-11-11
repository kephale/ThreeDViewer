package sc.iview.test;

import graphics.scenery.SceneryBase;
import io.scif.SCIFIOService;
import net.imagej.Dataset;
import net.imagej.ImageJService;
import net.imagej.ops.OpService;
import net.imglib2.IterableInterval;
import net.imglib2.img.Img;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import org.junit.Assert;
import org.scijava.Context;
import org.scijava.io.IOService;
import org.scijava.log.LogService;
import org.scijava.script.ScriptModule;
import org.scijava.script.ScriptService;
import org.scijava.service.SciJavaService;
import org.scijava.thread.ThreadService;
import sc.iview.SciView;
import sc.iview.SciViewService;
import sc.iview.commands.demo.ResourceLoader;

import javax.script.ScriptException;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.Future;

public class AllScripts {

    static public void main(String[] args) {
        SceneryBase.xinitThreads();

        System.setProperty( "scijava.log.level:sc.iview", "debug" );
        Context context = new Context( ImageJService.class, SciJavaService.class, SCIFIOService.class, ThreadService.class, ScriptService.class, LogService.class);

        // For developer debugging
//        UIService ui = context.service( UIService.class );
//        if( !ui.isVisible() ) ui.showUI();

        IOService io = context.service( IOService.class );
        OpService ops = context.service( OpService.class );
        LogService log = context.service( LogService.class );
        SciViewService sciViewService = context.service( SciViewService.class );
        SciView sciView = sciViewService.getOrCreateActiveSciView();

        ScriptService scriptService = context.service( ScriptService.class );

        File cubeFile = null;
        Dataset img = null;
        try {
            cubeFile = ResourceLoader.createFile( AllScripts.class, "/cored_cube_var2_8bit.tif" );
            img = (Dataset) io.open( cubeFile.getAbsolutePath() );
        } catch (IOException e) {
            e.printStackTrace();
        }

        HashMap<String,Object> testMap = new HashMap<>();
        testMap.put("sciView", sciView);
        testMap.put("img", img);

        HashMap<String, String> testScripts = new HashMap<String, String>();
        testScripts.put("/scripts/sphere_test.py", "/outputs/sphere_test.png" );
        testScripts.put("/scripts/volume_test.py", "/outputs/volume_test.png" );

        for( String scriptPath : testScripts.keySet() ) {
            String outputPath = testScripts.get(scriptPath);
            try {
                File scriptFile = ResourceLoader.createFile(AllScripts.class, scriptPath);
                File targetFile = ResourceLoader.createFile(AllScripts.class, outputPath);
                Img<UnsignedByteType> targetOutput = (Img<UnsignedByteType>) io.open(targetFile.getAbsolutePath());

                // Reset the scene to initial configuration
                sciView.reset();
                Thread.sleep(20);

                // Run script and block until done
                Future<ScriptModule> res = scriptService.run(scriptFile, true, testMap);
                while (!res.isDone()) {
                    Thread.sleep(20);
                }

                // Get the output generated by evaluating the script
                Img<UnsignedByteType> scriptOutput = sciView.getScreenshot();

                // Look at the difference between the current target and the
                IterableInterval<UnsignedByteType> diff = ops.math().subtract(targetOutput, (IterableInterval<UnsignedByteType>) scriptOutput);
                RealType sumDiff = ops.stats().sum(diff);


                Assert.assertEquals(sumDiff.getRealDouble(), 0, 0.1);

                //log.warn("Test: " + scriptPath + " passed" );
                System.out.println("Test: " + scriptPath + " passed" );

                // For developer debugging
                //ui.show("Diff", diff);
            } catch (IOException | ScriptException | InterruptedException e) {
                e.printStackTrace();
            }
        }

    }

}
