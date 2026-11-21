package com.kisapilot.kisaagent;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;

public class PpkKeyConverterTest {
    private byte[] str(byte[] b)throws Exception {ByteArrayOutputStream o=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(o);d.writeInt(b.length);d.write(b);return o.toByteArray();}
    private byte[] text(String s){return s.getBytes(StandardCharsets.US_ASCII);}
    private byte[] join(byte[]... parts)throws Exception{ByteArrayOutputStream o=new ByteArrayOutputStream();for(byte[] b:parts)o.write(b);return o.toByteArray();}
    private String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format("%02x",v&255));return s.toString();}
    private KeyPair key()throws Exception{KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);return g.generateKeyPair();}
    private byte[] fixture(KeyPair k,int version,boolean encrypted)throws Exception{
        RSAPrivateCrtKey r=(RSAPrivateCrtKey)k.getPrivate();
        byte[] pub=join(str(text("ssh-rsa")),str(r.getPublicExponent().toByteArray()),str(r.getModulus().toByteArray()));
        byte[] priv=join(str(r.getPrivateExponent().toByteArray()),str(r.getPrimeP().toByteArray()),str(r.getPrimeQ().toByteArray()),str(r.getCrtCoefficient().toByteArray()));
        byte[] pass=encrypted?text("fixture-password"):new byte[0],iv=new byte[16],aes=new byte[32],macKey=new byte[0];
        String cipher=encrypted?"aes256-cbc":"none",extra="";
        if(encrypted)priv=Arrays.copyOf(priv,(priv.length+15)/16*16);
        if(version==2){
            macKey=MessageDigest.getInstance("SHA-1").digest(join(text("putty-private-key-file-mac-key"),pass));
            if(encrypted)aes=Arrays.copyOf(join(MessageDigest.getInstance("SHA-1").digest(join(new byte[4],pass)),MessageDigest.getInstance("SHA-1").digest(join(new byte[]{0,0,0,1},pass))),32);
        } else if(encrypted){
            byte[] salt=text("fixture-salt-1234"),derived=new byte[80];
            Argon2BytesGenerator g=new Argon2BytesGenerator();g.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withSalt(salt).withMemoryAsKB(8192).withIterations(2).withParallelism(1).build());g.generateBytes(pass,derived);
            aes=Arrays.copyOfRange(derived,0,32);iv=Arrays.copyOfRange(derived,32,48);macKey=Arrays.copyOfRange(derived,48,80);
            extra="Key-Derivation: Argon2id\nArgon2-Memory: 8192\nArgon2-Passes: 2\nArgon2-Parallelism: 1\nArgon2-Salt: "+hex(salt)+"\n";
        }
        Mac mac=Mac.getInstance(version==2?"HmacSHA1":"HmacSHA256");mac.init(new SecretKeySpec(macKey.length==0?new byte[1]:macKey,mac.getAlgorithm()));
        String digest=hex(mac.doFinal(join(str(text("ssh-rsa")),str(text(cipher)),str(text("fixture")),str(pub),str(priv))));
        if(encrypted){Cipher c=Cipher.getInstance("AES/CBC/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(aes,"AES"),new IvParameterSpec(iv));priv=c.doFinal(priv);}
        return text("PuTTY-User-Key-File-"+version+": ssh-rsa\nEncryption: "+cipher+"\nComment: fixture\nPublic-Lines: 1\n"+Base64.getEncoder().encodeToString(pub)+"\n"+extra+"Private-Lines: 1\n"+Base64.getEncoder().encodeToString(priv)+"\nPrivate-MAC: "+digest+"\n");
    }
    private void check(int version,boolean encrypted)throws Exception{
        KeyPair k=key();byte[] result=PpkKeyConverter.convert(fixture(k,version,encrypted),"fixture-password".toCharArray());
        String pem=new String(result,StandardCharsets.US_ASCII);byte[] blob=Base64.getMimeDecoder().decode(pem.replace("-----BEGIN OPENSSH PRIVATE KEY-----","").replace("-----END OPENSSH PRIVATE KEY-----",""));
        RSAPrivateCrtKeyParameters parsed=(RSAPrivateCrtKeyParameters)OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(blob);
        assertEquals(((RSAPrivateCrtKey)k.getPrivate()).getModulus(),parsed.getModulus());
        assertEquals(((RSAPrivateCrtKey)k.getPrivate()).getPrivateExponent(),parsed.getExponent());
    }
    @Test public void rsaV2Unencrypted()throws Exception{check(2,false);}
    @Test public void rsaV2Encrypted()throws Exception{check(2,true);}
    @Test public void rsaV3Unencrypted()throws Exception{check(3,false);}
    @Test public void rsaV3Encrypted()throws Exception{check(3,true);}
    @Test public void wrongPasswordCannotImport()throws Exception{
        try{PpkKeyConverter.convert(fixture(key(),3,true),"wrong".toCharArray());fail();}catch(IOException e){assertEquals("PPK_PASSPHRASE_OR_MAC",e.getMessage());}
    }
    @Test public void modifiedMacCannotImport()throws Exception{
        String f=new String(fixture(key(),2,false),StandardCharsets.US_ASCII);f=f.replace("Comment: fixture","Comment: altered");
        try{PpkKeyConverter.convert(text(f),new char[0]);fail();}catch(IOException e){assertEquals("PPK_PASSPHRASE_OR_MAC",e.getMessage());}
    }
    @Test public void excessiveKdfIsRejectedBeforeAllocation()throws Exception{
        String f=new String(fixture(key(),3,true),StandardCharsets.US_ASCII).replace("Argon2-Memory: 8192","Argon2-Memory: 999999999");
        try{PpkKeyConverter.convert(text(f),new char[0]);fail();}catch(IOException e){assertEquals("PPK_KDF_LIMIT",e.getMessage());}
    }
}
