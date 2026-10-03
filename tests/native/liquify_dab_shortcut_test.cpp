// SPDX-License-Identifier: GPL-3.0-or-later
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>
#include "liquify_dab_shader.h"
#include "../../app/src/main/cpp/LiquifyInverseField.h"

GLuint compile(GLenum type, const char *source)
{
    GLuint s = glCreateShader(type);
    glShaderSource(s,1,&source,nullptr); glCompileShader(s);
    GLint ok; glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
    if (!ok) { char log[4096]; glGetShaderInfoLog(s,sizeof(log),nullptr,log); puts(log); }
    assert(ok); return s;
}
std::vector<float> render(const char *source, int mode, int step, bool professional = false,
                          float hardness = .5f, bool hold = false)
{
    constexpr int side = 96;
    GLuint p = glCreateProgram();
    glAttachShader(p,compile(GL_VERTEX_SHADER,
        "attribute vec2 aPos;void main(){gl_Position=vec4(aPos,0.,1.);}"));
    glAttachShader(p,compile(GL_FRAGMENT_SHADER,source));
    glBindAttribLocation(p,0,"aPos"); glLinkProgram(p);
    GLint ok; glGetProgramiv(p,GL_LINK_STATUS,&ok); assert(ok); glUseProgram(p);
    auto two = [&](const char *name,float x,float y) { glUniform2f(glGetUniformLocation(p,name),x,y); };
    two("uFieldOrigin",0,0); two("uFieldStep",step,step); two("uFieldTexSize",side,side);
    two("uOutputOffset",0,0);
    glUniform1i(glGetUniformLocation(p,"uOldField"),0);
    glUniform1i(glGetUniformLocation(p,"uDabMode"),mode);
    glUniform1f(glGetUniformLocation(p,"uDabRadius"),24.f*step);
    glUniform1f(glGetUniformLocation(p,"uDabGain"),.3f);
    glUniform1i(glGetUniformLocation(p,"uProfessional"),professional ? 1 : 0);
    glUniform1f(glGetUniformLocation(p,"uHardness"),hardness * .85f);
    GLuint textures[2], fbo; glGenTextures(2,textures); glGenFramebuffers(1,&fbo);
    glBindFramebuffer(GL_FRAMEBUFFER,fbo);
    std::vector<float> initial(side*side*4);
    for (int i=0;i<side*side;i++) { initial[i*4]=float(i%17)/8; initial[i*4+1]=-float(i%11)/8; }
    LiquifyInverseField cpu;
    if (professional) {
        std::fill(initial.begin(), initial.end(), 0.f);
        cpu.reset(side * step, side * step, step);
        glUniform1f(glGetUniformLocation(p,"uDabRadius"),40.f*step);
    }
    for (auto t:textures) {
        glBindTexture(GL_TEXTURE_2D,t);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA16F,side,side,0,GL_RGBA,GL_FLOAT,initial.data());
    }
    glViewport(0,0,side,side); glDisable(GL_BLEND);
    for (int i=0;i<40;i++) {
        two("uDabCenter",float(5+i*7%90)*step,float(2+i*13%92)*step);
        two("uDabDelta",i%2?3.f:-3.f,1.f);
        if (professional) {
            const float cx = float(5+i*7%90)*step, cy = float(2+i*13%92)*step;
            const float dx = hold ? 0.f : (i%2 ? 1.f : -1.f)*step;
            const float dy = hold ? 0.f : .5f*step;
            const float strength = hold ? .016f : .65f;
            const float gain = mode == 0 || mode >= 5 ? strength : strength *
                (hold ? (mode <= 2 ? 1.5f : 2.f) : std::hypot(dx,dy)/(80.f*step)*(mode <= 2 ? .8f : 1.2f));
            two("uDabDelta",dx,dy);
            glUniform1f(glGetUniformLocation(p,"uDabGain"),gain);
            cpu.apply(cx-dx/2,cy-dy/2,cx+dx/2,cy+dy/2,strength,80.f*step,mode,true,hardness);
        }
        glBindTexture(GL_TEXTURE_2D,textures[0]);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[1],0);
        assert(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE);
        glDrawArrays(GL_TRIANGLE_STRIP,0,4);
        glCopyTexSubImage2D(GL_TEXTURE_2D,0,0,0,0,0,side,side);
    }
    std::vector<float> out(side*side*4);
    glReadPixels(0,0,side,side,GL_RGBA,GL_FLOAT,out.data()); assert(glGetError()==GL_NO_ERROR);
    if (professional) {
        float error = 0;
        for (int y=0;y<side;++y) for (int x=0;x<side;++x) {
            auto expected = cpu.sample((x+.5f)*step,(y+.5f)*step);
            const int b=(y*side+x)*4;
            error = std::max(error, std::abs(out[b]+out[b+2]-expected.x));
            error = std::max(error, std::abs(out[b+1]+out[b+3]-expected.y));
        }
        if (error > .002f) std::printf("CPU/GPU mode %d step %d hardness %.2f hold %d error %.9f\n",
            mode,step,hardness,hold,error);
        assert(error <= .002f);
    }
    glDeleteTextures(2,textures); glDeleteFramebuffers(1,&fbo); glDeleteProgram(p);
    return out;
}
int main()
{
    auto d=eglGetDisplay(EGL_DEFAULT_DISPLAY); assert(eglInitialize(d,nullptr,nullptr));
    EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,EGL_NONE};
    EGLConfig cfg; EGLint n; assert(eglChooseConfig(d,attrs,&cfg,1,&n)&&n);
    EGLint pa[]={EGL_WIDTH,96,EGL_HEIGHT,96,EGL_NONE},ca[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
    auto surf=eglCreatePbufferSurface(d,cfg,pa); auto ctx=eglCreateContext(d,cfg,EGL_NO_CONTEXT,ca);
    assert(eglMakeCurrent(d,surf,surf,ctx));
    float quad[]={-1,-1,1,-1,-1,1,1,1}; GLuint vbo; glGenBuffers(1,&vbo); glBindBuffer(GL_ARRAY_BUFFER,vbo);
    glBufferData(GL_ARRAY_BUFFER,sizeof(quad),quad,GL_STATIC_DRAW);
    glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,nullptr); glEnableVertexAttribArray(0);
    float maxError=0;
    for (int step:{1,2,4}) for (int mode=0;mode<5;mode++) {
        auto a=render(referenceDab,mode,step), b=render(productionDab,mode,step);
        for (size_t i=0;i<a.size();i+=4) for (int c=0;c<2;c++)
            maxError=std::max(maxError,std::abs((a[i+c]+a[i+c+2])-(b[i+c]+b[i+c+2])));
    }
    std::printf("production DAB shader: five modes, three resolutions, 40 passes, max error %.9f px\n",maxError);
    assert(maxError<=.001f);
    for (int step:{1,2,4}) for (float hardness:{0.f,.5f,1.f}) for (int mode=0;mode<7;++mode) {
        render(productionDab,mode,step,true,hardness);
        if (mode >= 1 && mode <= 4) render(productionDab,mode,step,true,hardness,true);
    }
    std::puts("professional CPU/GPU parity: seven modes, hardness 0/50/100%, step 1/2/4, stationary holds PASS (<0.002px)");
}
