package app.projectv7.wrapper;

import java.io.*;
import java.util.zip.ZipFile;

final class GuestObbImporter {
    interface Progress { void update(long bytes); }
    private GuestObbImporter(){}
    static boolean validName(String s){
        return s!=null && s.length()>4 && s.length()<=180 &&
            s.matches("[A-Za-z0-9._-]+") && s.toLowerCase(java.util.Locale.ROOT).endsWith(".obb");
    }
    static File copy(InputStream input,File directory,String expected,long size,boolean zipRequired,Progress progress)throws IOException {
        if(input==null||!validName(expected))throw new IOException("Invalid expansion request.");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Could not prepare storage.");
        File root=directory.getCanonicalFile(),target=new File(root,expected);
        if(!target.equals(target.getCanonicalFile()))throw new IOException("Unsafe destination.");
        if(size>0&&size>root.getUsableSpace())throw new IOException("Not enough free storage.");
        File temp=File.createTempFile(".pv7-import-",".partial",root);
        try {
            long count=0,last=0;byte[] buffer=new byte[256*1024];
            try(FileOutputStream out=new FileOutputStream(temp)){
                int n;
                while((n=input.read(buffer))!=-1){
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Import canceled.");
                    if(n==0)continue;
                    out.write(buffer,0,n);count+=n;
                    if(count-last>=4*1024*1024L){last=count;if(progress!=null)progress.update(count);}
                }
                out.getFD().sync();
            }
            if(count==0||(size>=0&&count!=size))throw new IOException("File is empty or incomplete.");
            if(progress!=null)progress.update(count);
            if(zipRequired){
                try(ZipFile archive=new ZipFile(temp)){
                    if(archive.size()==0)throw new IOException("Empty OBB archive.");
                }catch(java.util.zip.ZipException e){throw new IOException("This game requires a readable ZIP-based OBB.",e);}
            }
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Import canceled.");
            if(!temp.renameTo(target))throw new IOException("Could not save OBB; existing file was left unchanged.");
            return target;
        }finally{if(temp.exists())temp.delete();}
    }
}
