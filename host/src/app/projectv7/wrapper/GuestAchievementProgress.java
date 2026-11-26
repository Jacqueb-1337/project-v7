package app.projectv7.wrapper;
/** Local progress, independent of the discontinued remote achievement service. */
final class GuestAchievementProgress {
    static int increment(int prior,int delta) {
        if(delta<=0)return Math.max(0,prior);
        return (int)Math.min(Integer.MAX_VALUE,(long)Math.max(0,prior)+delta);
    }
}
