package io.github.muntashirakon.crypto.spake2;
import org.junit.Test;
import static org.junit.Assert.*;
public class NativeSpakeTest {
 private byte[] bytes(String s){return s.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
 private byte[][] exchange(String a,String b,String serverName){
  Spake2Context client=new Spake2Context(Spake2Role.Alice,bytes("adb pair client\0"),bytes("adb pair server\0"));
  Spake2Context server=new Spake2Context(Spake2Role.Bob,bytes(serverName),bytes("adb pair client\0"));
  try { byte[] cm=client.generateMessage(bytes(a)); byte[] sm=server.generateMessage(bytes(b));return new byte[][]{client.processMessage(sm),server.processMessage(cm)}; }
  finally {client.destroy();server.destroy();}
 }
 @Test public void authenticatedExchange(){for(int i=0;i<20;i++){byte[][] k=exchange("123456-exporter","123456-exporter","adb pair server\0");assertArrayEquals(k[0],k[1]);}}
 @Test public void wrongPasswordRejected(){byte[][] k=exchange("123456-exporter","654321-exporter","adb pair server\0");assertFalse(java.util.Arrays.equals(k[0],k[1]));}
 @Test public void wrongIdentityRejected(){byte[][] k=exchange("123456-exporter","123456-exporter","wrong server");assertFalse(java.util.Arrays.equals(k[0],k[1]));}
 @Test public void malformedMessageRejected(){Spake2Context c=new Spake2Context(Spake2Role.Alice,bytes("client"),bytes("server"));try{c.generateMessage(bytes("password"));assertThrows(IllegalStateException.class,()->c.processMessage(new byte[3]));}finally{c.destroy();c.destroy();}}
}
