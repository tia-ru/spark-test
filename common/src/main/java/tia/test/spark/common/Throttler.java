package tia.test.spark.common;

public class Throttler {
    private final int pauseMs;
    private long prevCallTime = 0;

    public Throttler(int pauseMs) {
        this.pauseMs = pauseMs;
    }
    public void pause(){
        long now = System.currentTimeMillis();
        long delta = now - prevCallTime;
        if (delta < pauseMs){
            try {
                Thread.sleep(pauseMs - delta);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            prevCallTime = System.currentTimeMillis();
        } else {
            prevCallTime = now;
        }
    }
}
