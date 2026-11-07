#!/usr/bin/env python3
"""Build the vendored SPAKE2 JNI bridge. Requires JDK, GCC/G++ and NDK r27c."""
import os,subprocess,pathlib
root=pathlib.Path(__file__).resolve().parent.parent
src=root/'native-spake/src'
jdk=pathlib.Path(os.environ['JAVA_HOME'])
def run(args): subprocess.run(list(map(str,args)),check=True)
host=root/'native-spake/host';host.mkdir(parents=True,exist_ok=True)
for name in ['sha512','spake2']:
 run(['gcc','-fPIC','-O2','-I',src/'include','-c',src/(name+'.c'),'-o',host/(name+'.o')])
run(['g++','-shared','-fPIC','-O2','-Wno-write-strings','-I',jdk/'include','-I',jdk/'include/linux','-I',src/'include',src/'spake2_jni.cpp',host/'spake2.o',host/'sha512.o','-o',host/'libspake2.so'])
ndk=pathlib.Path(os.environ['ANDROID_HOME'])/'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin'
for abi,target in [('arm64-v8a','aarch64-linux-android28'),('armeabi-v7a','armv7a-linux-androideabi28'),('x86_64','x86_64-linux-android28'),('x86','i686-linux-android28')]:
 out=root/'native-spake/build'/abi;out.mkdir(parents=True,exist_ok=True)
 for name in ['sha512','spake2']:
  run([ndk/(target+'-clang'),'-fPIC','-O2','-I',src/'include','-c',src/(name+'.c'),'-o',out/(name+'.o')])
 dest=root/'pairing/src/main/jniLibs'/abi;dest.mkdir(parents=True,exist_ok=True)
 run([ndk/(target+'-clang++'),'-shared','-static-libstdc++','-fPIC','-O2','-Wno-write-strings','-Wl,-z,max-page-size=16384','-I',src/'include',src/'spake2_jni.cpp',out/'spake2.o',out/'sha512.o','-o',dest/'libspake2.so'])
