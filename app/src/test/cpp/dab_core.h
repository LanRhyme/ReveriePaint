// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once
#include "AirbrushTickGate.h"
#include <cstdio>
#include <cmath>
#include <vector>
#include <string>
#include <algorithm>
#include <random>
using std::vector;
struct Pt { double x=0,y=0; };
inline double len(Pt a, Pt b){ return std::hypot(b.x-a.x,b.y-a.y); }
struct PI { Pt pos; double time=0; };           // 只保留门控所需字段 (位置/时间ms)
inline PI mix(double t, const PI&a, const PI&b){ PI r; r.pos={a.pos.x+t*(b.pos.x-a.pos.x), a.pos.y+t*(b.pos.y-a.pos.y)}; r.time=a.time+t*(b.time-a.time); return r; }
inline double directionBetweenPoints(Pt p1, Pt p2, double def){
    if (std::fabs(p1.x-p2.x)<1e-9 && std::fabs(p1.y-p2.y)<1e-9) return def; // fuzzyPointCompare 近似
    return std::atan2(p2.y-p1.y, p2.x-p1.x);
}
inline const double MIN_DISTANCE_SPACING = 0.5, MIN_TIMED_INTERVAL = 0.5, MAX_TIMED_INTERVAL = 1e9;
struct Dab { double x,y,angle,t; char src; };   // src: 'P' paintLine, 'T' airbrush tick
struct Dist {
    double spacing=10; bool timingOn=false; double interval=33.3;
    double accumDist=0, accumTime=0; Pt lastPos; double lastAngle=0; bool lastValid=false;
    void resetAcc(){ accumDist=0; accumTime=0; }
    double isotropic(Pt s, Pt e){                       // kis_distance_information.cpp getNextPointPositionIsotropic
        double d=accumDist, sp=std::max(MIN_DISTANCE_SPACING, spacing);
        if (s.x==e.x && s.y==e.y) return -1;
        double drag=len(s,e), next=sp-d, t;
        if (next<=0.0){ resetAcc(); t=0.0; }
        else if (next<=drag){ t=next/drag; resetAcc(); }
        else { t=-1; accumDist+=drag; }
        return t;
    }
    double timed(double st, double et){                 // getNextPointPositionTimed
        if (!(st<et)) return -1.0;
        double iv=std::min(std::max(interval,MIN_TIMED_INTERVAL),MAX_TIMED_INTERVAL);
        double next=iv-accumTime, t;
        if (next<=0.0){ resetAcc(); t=0.0; }
        else if (next<=et-st){ resetAcc(); t=next/(et-st); }
        else { accumTime+=et-st; t=-1.0; }
        return t;
    }
    double next(Pt s, Pt e, double st, double et){      // getNextPointPosition (distance + time, min())
        double df=isotropic(s,e), tf=-1.0;
        if (timingOn) tf=timed(st,et);
        if (df<0) return tf; if (tf<0) return df; return std::min(df,tf);
    }
    void registerDab(const PI& pi, double angle){ lastAngle=angle; lastPos=pi.pos; lastValid=true; }
};
struct Sim {
    Dist d; vector<Dab> dabs;
    void paintAt(const PI& pi, char src){               // KisPaintInformation::paintAt: 先画 (角度=lastPos->pos), 再 registerPaintedDab
        double ang=directionBetweenPoints(d.lastPos, pi.pos, d.lastAngle);
        dabs.push_back({pi.pos.x,pi.pos.y,ang,pi.time,src});
        d.registerDab(pi, ang);
    }
    void paintLine(const PI& p1, const PI& p2){         // kis_paintop_utils.h paintLine (无 fan)
        Pt end=p2.pos; double endT=p2.time; PI pi=p1; double t;
        while ((t=d.next(pi.pos,end,pi.time,endT))>=0.0){ pi=mix(t,pi,p2); paintAt(pi,'P'); }
    }
};
struct Ev { Pt p; double t; };                           // 输入事件 (时间 ms)
inline vector<Ev> makeLine(double v, double t0, double t1, double dt, Pt origin, double jitter, unsigned seed, double tStart){
    vector<Ev> e; std::mt19937 g(seed); std::normal_distribution<double> n(0,1);
    for(double t=t0;t<=t1+1e-9;t+=dt){ Ev x; x.t=t; x.p={origin.x+v*(t-tStart), origin.y+jitter*n(g)}; e.push_back(x);} return e;
}
// ---- Krita 参考: 每个事件对直接 paintLine (freehand helper 行为); airbrush 暂停期按 interval*0.5 周期喂同点推进时间的 paintLine
inline Sim runKrita(const vector<Ev>& ev, double spacing, bool timing, double interval, double pauseFillEnd=-1){
    Sim s; s.d.spacing=spacing; s.d.timingOn=timing; s.d.interval=interval;
    s.d.lastPos=ev[0].p; s.d.lastValid=true;             // RP/Krita 都以 start 初始化 KisDistanceInformation(start,0)
    for(size_t i=1;i<ev.size();++i){
        PI a{ev[i-1].p,ev[i-1].t}, b{ev[i].p,ev[i].t};
        if (timing && b.time-a.time>interval*0.5+1e-9){ // 事件间隔内 Krita 定时器会以 interval*0.5 周期补点 (同位置, 时间推进)
            double tt=a.time; PI cur=a;
            while (tt+interval*0.5 < b.time-1e-9){ tt+=interval*0.5; PI n{a.pos,tt}; s.paintLine(cur,n); cur=n; }
            s.paintLine(cur,b);
        } else s.paintLine(a,b);
    }
    return s;
}
// ---- RP 管线: 抽稀 -> 按 flushMs 分批 -> paintLine(carry 起) ; tickMs>0 时按墙钟 tickMs 周期对"最后样本"调用 paintAt(免门控)
struct RPCfg { double spacing; bool timing; double interval; double thin; double flushMs; double tickMs; bool gated=false; };
inline Sim runRP(const vector<Ev>& ev, const RPCfg& c){
    Sim s; s.d.spacing=c.spacing; s.d.timingOn=c.timing; s.d.interval=c.interval;
    s.d.lastPos=ev[0].p; s.d.lastValid=true;
    vector<Ev> samples{ev[0]}; size_t carry=1; double lastFlush=ev[0].t, nextTick=c.tickMs>0? ev[0].t+c.tickMs : 1e18;
    auto flush=[&](){
        for(size_t i=std::max<size_t>(1,carry); i<samples.size(); ++i){
            if (len(samples[i-1].p,samples[i].p)<1e-3) continue;
            s.paintLine({samples[i-1].p,samples[i-1].t},{samples[i].p,samples[i].t});
        }
        carry=samples.size();
    };
    reverie::AirbrushTickGate gate; gate.reset(ev[0].t);
    auto tickUntil=[&](double now){
        while (nextTick<=now){
            double t=nextTick; nextTick+=c.tickMs;
            if (!c.gated){ PI p{samples.back().p,t}; s.paintAt(p,'T'); continue; }   // 现状: 免门控 paintAt
            double start;                                  // 方案: 无位移才放行, 走 paintLine(同点, 时间推进), 由 KisDistanceInformation 门控
            if (!gate.poll(t,start)) continue;
            PI a{samples.back().p,start}, b{samples.back().p,t};
            s.paintLine(a,b);
            samples.back().t=t;                            // 末样本时间前移, 下批 flush 不再重复消耗这段停笔时间
        }
    };
    for(size_t i=1;i<ev.size();++i){
        if (len(ev[i-1].p,ev[i].p)>1e-3) gate.onMotion(ev[i].t);
        double dist=len(samples.back().p, ev[i].p);
        if (dist<c.thin){ if (samples.size()>carry){ samples.back().t=ev[i].t; } /* 位置不更新 (原文只改属性) */ }
        else samples.push_back(ev[i]);
        if (ev[i].t-lastFlush>=c.flushMs){ flush(); lastFlush=ev[i].t; tickUntil(ev[i].t); } // tick 与批同在渲染线程串行: 只会落在批与批之间 (此时样本已全部 flush)
    }
    flush(); tickUntil(ev.back().t);
    return s;
}
