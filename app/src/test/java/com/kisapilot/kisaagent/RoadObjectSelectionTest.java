package com.kisapilot.kisaagent;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class RoadObjectSelectionTest {
    @Test public void vehicleAndTrafficLightRemainSeparate(){
        RoadObjectDetector.Box vehicle=new RoadObjectDetector.Box(new float[]{.3f,.4f,.7f,.8f},"car",.8f);
        RoadObjectDetector.Box signal=new RoadObjectDetector.Box(new float[]{.45f,.1f,.5f,.2f},"traffic light",.9f);
        RoadObjectDetector.Result r=RoadObjectDetector.select(Arrays.asList(vehicle,signal));
        assertArrayEquals(vehicle.roi,r.vehicle,0);assertArrayEquals(signal.roi,r.signal,0);
    }
    @Test public void noDetectionsDoNotReuseOldTarget(){RoadObjectDetector.Result r=RoadObjectDetector.select(Collections.emptyList());assertNull(r.vehicle);assertNull(r.signal);}
    @Test public void sideVehicleOutsideSearchBandIsNotSelected(){
        RoadObjectDetector.Result r=RoadObjectDetector.select(Arrays.asList(new RoadObjectDetector.Box(new float[]{0,.3f,.1f,.9f},"car",.9f)));
        assertNull(r.vehicle);
    }
    @Test public void unknownObjectIsNeverPromotedToVehicle(){
        RoadObjectDetector.Result r=RoadObjectDetector.select(Arrays.asList(new RoadObjectDetector.Box(new float[]{.2f,.3f,.8f,.9f},"person",.9f)));
        assertNull(r.vehicle);
    }
}
