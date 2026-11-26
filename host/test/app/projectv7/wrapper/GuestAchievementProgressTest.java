package app.projectv7.wrapper;
public final class GuestAchievementProgressTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args) {
        check(GuestAchievementProgress.increment(0,1)==1);
        check(GuestAchievementProgress.increment(5,3)==8);
        check(GuestAchievementProgress.increment(5,0)==5);
        check(GuestAchievementProgress.increment(5,-2)==5);
        check(GuestAchievementProgress.increment(Integer.MAX_VALUE-1,5)==Integer.MAX_VALUE);
        check(GuestAchievementProgress.increment(-3,2)==2);
        System.out.println("6 achievement progress checks passed");
    }
}
