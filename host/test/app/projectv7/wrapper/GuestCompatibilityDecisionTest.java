package app.projectv7.wrapper;
public final class GuestCompatibilityDecisionTest {
    private static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        String[][] cases={{"(I)V","Skip call (void)"},{"()Z","false"},{"(I)I","0"},{"()Ljava/lang/String;","null"},{"([I)[Ljava/lang/String;","null"},{"()B","0"},{"()C","0"},{"()S","0"}};
        for(String[] c:cases)check(c[1].equals(GuestCompatibilityDecision.fallback("method",c[0])));
        for(String s:new String[]{"()F","()D","()J","(V)V","()Vjunk","()L;","bad","()[V","(I"})check(GuestCompatibilityDecision.fallback("method",s)==null);
        check(GuestCompatibilityDecision.fallback("bad name","()V")==null);
        GuestCompatibilityDecision allow=new GuestCompatibilityDecision("method","0");
        Thread worker=new Thread(()->allow.resolve(true));worker.start();
        check(allow.await(1000));worker.join();allow.resolve(false);check(allow.await(1));
        GuestCompatibilityDecision stop=new GuestCompatibilityDecision("method","0");
        stop.resolve(false);stop.resolve(true);check(!stop.await(1));
        GuestCompatibilityDecision timeout=new GuestCompatibilityDecision("method","0");
        check(!timeout.await(1));timeout.resolve(true);check(!timeout.await(1));
        String report=GuestCompatibilityEvents.issueReport("game.pkg","16","Cocos","16","phone",
            new GuestCompatibilityEvents.Event("Cocos","fetchPersonalBest(I)V","unresolved",true));
        check(report.contains("fetchPersonalBest(I)V")&&report.contains("Version code: 16"));
        System.out.println("Decision and report checks passed");
    }
}