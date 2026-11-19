package com.ali.depthscenes;

/** Measured extents in the 690 x 1536 Pixel captures with the two-line system clock. */
public final class ClockBandTest {
    public static void main(String[] args) {
        int h=1536,w=690;
        if(!ClockBand.clockCandidate(w,h,149,439,528,864)) throw new AssertionError("Two-line clock rejected");
        if(ClockBand.top(h)>439 || ClockBand.bottom(h)<=863 || ClockBand.bottom(h)>=914)throw new AssertionError("Clock or date clipped incorrectly");
        if(ClockBand.clockCandidate(w,h,150,439,528,925))throw new AssertionError("Date accepted as a clock");
        System.out.println("Captured clock, date and two-line bounds passed");
    }
}
