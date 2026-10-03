#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <cassert>
#include <cstdio>
#include <vector>
#include "liquify_scene_shader.h"
GLuint shader(GLenum kind,const char* text){GLuint s=glCreateShader(kind);glShaderSource(s,1,&text,nullptr);glCompileShader(s);GLint ok;glGetShaderiv(s,GL_COMPILE_STATUS,&ok);if(!ok){char log[4096];glGetShaderInfoLog(s,4096,nullptr,log);puts(log);}assert(ok);return s;}
int main(){
 auto d=eglGetDisplay(EGL_DEFAULT_DISPLAY);assert(eglInitialize(d,nullptr,nullptr));
 EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};EGLConfig cfg;EGLint n;assert(eglChooseConfig(d,attrs,&cfg,1,&n)&&n);
 EGLint pa[]={EGL_WIDTH,8,EGL_HEIGHT,8,EGL_NONE},ca[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};auto surf=eglCreatePbufferSurface(d,cfg,pa);auto ctx=eglCreateContext(d,cfg,EGL_NO_CONTEXT,ca);assert(eglMakeCurrent(d,surf,surf,ctx));
 auto p=glCreateProgram();glAttachShader(p,shader(GL_VERTEX_SHADER,"attribute vec2 aPos;void main(){gl_Position=vec4(aPos,0.,1.);}"));glAttachShader(p,shader(GL_FRAGMENT_SHADER,productionFS));glBindAttribLocation(p,0,"aPos");glLinkProgram(p);GLint ok;glGetProgramiv(p,GL_LINK_STATUS,&ok);assert(ok);glUseProgram(p);
 auto two=[&](const char* k,float a,float b){glUniform2f(glGetUniformLocation(p,k),a,b);};
 two("uViewSize",8,8);two("uOrigin",0,0);two("uEx",1,0);two("uEy",0,1);two("uCropOrigin",0,0);two("uCropSize",8,8);two("uDrawOrigin",0,0);two("uDrawSize",8,8);two("uGridOrigin",0,0);two("uGridStep",8,8);two("uGridSize",2,2);
 GLuint tex[2];glGenTextures(2,tex);std::vector<unsigned char> src(8*8*4); // transparent old position, half-alpha moved mark
 for(int y=0;y<8;y++)for(int x=4;x<8;x++){src[(y*8+x)*4]=100;src[(y*8+x)*4+3]=128;}
 unsigned char zero[16]={};for(int i=0;i<2;i++){glActiveTexture(GL_TEXTURE0+i);glBindTexture(GL_TEXTURE_2D,tex[i]);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,i?2:8,i?2:8,0,GL_RGBA,GL_UNSIGNED_BYTE,i?zero:src.data());}
 glUniform1i(glGetUniformLocation(p,"uSrc"),0);glUniform1i(glGetUniformLocation(p,"uGrid"),1);glUniform1i(glGetUniformLocation(p,"uField"),1);
 float quad[]={-1,-1,1,-1,-1,1,1,1};GLuint vbo;glGenBuffers(1,&vbo);glBindBuffer(GL_ARRAY_BUFFER,vbo);glBufferData(GL_ARRAY_BUFFER,sizeof(quad),quad,GL_STATIC_DRAW);glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,nullptr);glEnableVertexAttribArray(0);glViewport(0,0,8,8);
 glEnable(GL_BLEND);glBlendFunc(GL_ONE,GL_ONE_MINUS_SRC_ALPHA);std::vector<unsigned char> out(256);
 GLuint base;glGenTextures(1,&base);glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,base);
 glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
 std::vector<unsigned char> underlay(256);for(int i=0;i<64;i++){underlay[i*4+1]=255;underlay[i*4+3]=255;}
 glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,8,8,0,GL_RGBA,GL_UNSIGNED_BYTE,underlay.data());
 glUniform1i(glGetUniformLocation(p,"uUnderlay"),2);
 for(int pass=0;pass<3;pass++){
   // A conspicuous old green canvas must not contribute any pixel to the scene.
   glClearColor(0,1,0,1);glClear(GL_COLOR_BUFFER_BIT);two("uSceneSize",pass==1?0:8,pass==1?0:8);if(pass){glDisable(GL_BLEND);}
   glUniform1f(glGetUniformLocation(p,"uUseUnderlay"),pass==2?1.f:0.f);
   glDrawArrays(GL_TRIANGLE_STRIP,0,4);glReadPixels(0,0,8,8,GL_RGBA,GL_UNSIGNED_BYTE,out.data());assert(glGetError()==GL_NO_ERROR);
   for(int y=0;y<8;y++)for(int x=0;x<8;x++){int i=(y*8+x)*4;if(pass==2){assert(out[i+3]==255);assert(out[i]==(x<4?0:100));assert(out[i+1]==(x<4?255:127));}else if(pass==1){assert(out[i+3]==(x<4?0:128));assert(out[i]==(x<4?0:100));}else{assert(out[i+3]==255);if(x<4){assert(out[i]>=228);assert(out[i+1]>=230);}else{assert(out[i]>=213);assert(out[i+1]<=128);}}}
 }
 puts("PASS production GLSL: old paint replaced; static underlay composes below warped layer; commit preserves zero/half alpha");
 eglMakeCurrent(d,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);eglDestroyContext(d,ctx);eglDestroySurface(d,surf);eglTerminate(d);
}
