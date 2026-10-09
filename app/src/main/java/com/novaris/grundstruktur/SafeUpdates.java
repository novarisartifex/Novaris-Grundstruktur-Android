package com.novaris.grundstruktur;
import android.content.Context;import org.json.JSONObject;import java.io.*;import java.net.*;import java.security.*;import java.nio.charset.StandardCharsets;
public final class SafeUpdates {
 private SafeUpdates(){} private static final long MAX=200L*1024*1024;
 private static java.net.HttpURLConnection connect(String address)throws Exception{URL u=new URL(address);if(!"https".equalsIgnoreCase(u.getProtocol()))throw new IOException("HTTPS required");HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);int code=c.getResponseCode();if(code!=200)throw new IOException("HTTP "+code);return c;}
 public static JSONObject manifest(String url)throws Exception{HttpURLConnection c=connect(url);try(InputStream in=c.getInputStream()){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>131072)throw new IOException("Manifest too large");out.write(b,0,n);}JSONObject o=new JSONObject(out.toString("UTF-8"));if(o.getInt("schema_version")!=1)throw new IOException("Manifest schema mismatch");return o;}finally{c.disconnect();}}
 public static void install(Context ctx,JSONObject release)throws Exception{
  if(release.getInt("min_app_version_code")>1)throw new IOException("App update required");
  int next=release.getInt("data_version"),previous=ctx.getSharedPreferences("updates",0).getInt("version",0);
  if(next<=previous)throw new IOException("Version not newer");
  String hash=release.getString("sha256");if(!hash.matches("[a-f0-9]{64}"))throw new IOException("Bad checksum");
  long expected=release.getLong("size_bytes");if(expected<=0||expected>MAX)throw new IOException("Bad file size");
  File dir=ctx.getFilesDir(),stage=new File(dir,"compendium.html.new"),active=new File(dir,"compendium.html"),backup=new File(dir,"compendium.html.bak");
  if(stage.exists()&&!stage.delete())throw new IOException("Staging unavailable");
  try{HttpURLConnection c=connect(release.getString("url"));MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;
   try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(stage)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){bytes+=n;if(bytes>MAX||bytes>expected)throw new IOException("Oversized update");digest.update(b,0,n);out.write(b,0,n);}out.getFD().sync();}finally{c.disconnect();}
   if(bytes!=expected)throw new IOException("Size mismatch");
   StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));if(!hash.equals(hex.toString()))throw new IOException("Checksum mismatch");
   byte[] head=new byte[1024];int n;try(InputStream in=new FileInputStream(stage)){n=in.read(head);}String text=new String(head,0,Math.max(0,n),StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
   if(!text.contains("<!doctype html")||!text.contains("<html"))throw new IOException("Invalid HTML content");
   if(backup.exists()&&!backup.delete())throw new IOException("Old backup unavailable");
   if(active.exists()&&!active.renameTo(backup))throw new IOException("Backup failed");
   if(!stage.renameTo(active)){if(backup.exists())backup.renameTo(active);throw new IOException("Activation failed");}
   if(!ctx.getSharedPreferences("updates",0).edit().putInt("version",next).commit()){active.delete();if(backup.exists())backup.renameTo(active);throw new IOException("Version persistence failed");}
   backup.delete();
  }finally{stage.delete();}
 }
}
