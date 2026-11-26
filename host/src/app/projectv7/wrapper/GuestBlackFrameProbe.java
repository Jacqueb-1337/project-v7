package app.projectv7.wrapper;

/** Black frames are evidence only when paired with an unresolved OBB request. */
final class GuestBlackFrameProbe {
    private long firstBlack=-1;
    private int samples;
    void reset(){firstBlack=-1;samples=0;}
    boolean observe(boolean failedRequest,boolean black,long now){
        if(!failedRequest||!black){reset();return false;}
        if(firstBlack<0){firstBlack=now;samples=1;}
        else samples++;
        return samples>=4 && now-firstBlack>=3000;
    }
    static boolean isBlack(int[] pixels){
        if(pixels.length==0)return false;
        for(int pixel:pixels)if((pixel&0x00ffffff)!=0)return false;
        return true;
    }
}
