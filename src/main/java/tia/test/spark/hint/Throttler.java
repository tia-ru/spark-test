package tia.test.spark.hint;

public class Throttler {
    private final int pauseMs;
    private long prevCallTime = 0;

    public Throttler(int pauseMs) {
        this.pauseMs = pauseMs;
    }
    public void pause(){
        long now = System.currentTimeMillis();
        if (now - prevCallTime < pauseMs){
            try {
                Thread.sleep(pauseMs - now + prevCallTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            prevCallTime = System.currentTimeMillis();
        } else {
            prevCallTime = now;
        }
    }
}
