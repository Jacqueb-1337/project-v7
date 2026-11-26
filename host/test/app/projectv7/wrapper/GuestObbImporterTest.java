package app.projectv7.wrapper;
import java.io.*;
import java.util.zip.*;
public final class GuestObbImporterTest {
    static void check(boolean v){if(!v)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        File root=java.nio.file.Files.createTempDirectory("pv7-obb").toFile();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(bytes)){z.putNextEntry(new ZipEntry("assets/game"));z.write(123);z.closeEntry();}
        byte[] data=bytes.toByteArray();
        String name="main.16.test.game.obb";
        try{
            File f=GuestObbImporter.copy(new ByteArrayInputStream(data),root,name,data.length,true,null);
            check(f.getName().equals(name));check(f.length()==data.length);
            check(!GuestObbImporter.validName("../test.obb"));check(!GuestObbImporter.validName("a/b.obb"));
            try{GuestObbImporter.copy(new ByteArrayInputStream(new byte[]{1}),root,name,1,true,null);throw new AssertionError();}catch(IOException expected){}
            check(f.length()==data.length);
            try{GuestObbImporter.copy(new ByteArrayInputStream(data),root,name,data.length+1,true,null);throw new AssertionError();}catch(IOException expected){}
            check(f.length()==data.length);
            check(root.list().length==1);
            check(GuestObbImporter.copy(new ByteArrayInputStream(new byte[]{42}),root,"patch.1.test.obb",1,false,null).length()==1);
            System.out.println("OBB import checks passed");
        }finally{for(File f:root.listFiles())f.delete();root.delete();}
    }
}
