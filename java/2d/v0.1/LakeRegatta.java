import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

public class LakeRegatta extends JPanel implements ActionListener {

    /* ==================== 常量 ==================== */
    static final double TAU = Math.PI * 2;
    static final double EYE0 = 0.30, EYE1 = 0.62;
    static final double SAIL_MIN = 0.10, SAIL_MAX = 1.45, LUFF = 0.13;
    static final double BOUND_X = 144, BOUND_Z = 104, RING_R = 8;
    static final int MENU = 0, COUNT = 1, RACE = 2;

    /* ==================== 数学工具 ==================== */
    static double norm(double a) { a %= TAU; if (a > Math.PI) a -= TAU; if (a < -Math.PI) a += TAU; return a; }
    static double clamp(double v, double a, double b) { return v < a ? a : (v > b ? b : v); }
    static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    static double ss(double a, double b, double x) { double k = clamp((x - a) / (b - a), 0, 1); return k * k * (3 - 2 * k); }
    static double fr(double v) { return v - Math.floor(v); }
    static int sg(double v) { return v < 0 ? -1 : 1; }

    /* ==================== 风 ==================== */
    final double windBase = 0.25;
    double windDir = 0.25, windSpd = 11;
    boolean gustOn = false;
    double gustD = 0, gustW = 90;
    final long windSeed = 12345;

    double gustFactor(double x, double z) {
        if (!gustOn) return 1;
        double ux = Math.cos(windDir), uy = Math.sin(windDir);
        double p = x * ux + z * uy;
        return (p > gustD && p < gustD + gustW) ? 1.38 : 1;
    }

    void updateWind(double t) {
        windDir = norm(windBase + 0.34 * Math.sin(t * 0.043) + 0.24 * Math.sin(t * 0.017 + 2));
        windSpd = 10.5 + 3.2 * Math.sin(t * 0.071) + 1.6 * Math.sin(t * 0.023 + 5);
        double cyc = Math.floor(t / 26), lt = t - cyc * 26;
        double h1 = fr(Math.sin(cyc * 127.1 + windSeed * 13.7) * 43758.5453);
        double h2 = fr(Math.sin(cyc * 311.7 + windSeed * 7.3) * 12345.678);
        double off = 7 + h1 * 7, dur = 9 + h2 * 5;
        if (lt >= off && lt < off + dur) {
            gustOn = true;
            gustW = 85 + h1 * 40;
            gustD = 200 - (lt - off) * (650 / dur);
        } else gustOn = false;
    }

    /* ==================== 航线 ==================== */
    static class Gate {
        String name = "";
        boolean mark = false;
        double cx, cy, nx, ny;
        double ax, ay, bx, by, abx, aby, len2;
    }

    final Gate[] gates = { new Gate(), new Gate(), new Gate(), new Gate() };
    final String[] LEGS = { "冲起航线", "抢上风标", "横渡三角标", "冲终点线" };

    void mkGate(Gate g, double cx, double cy, double nx, double ny, double hw) {
        g.cx = cx; g.cy = cy; g.nx = nx; g.ny = ny;
        if (hw > 0) {
            g.ax = cx - ny * hw; g.ay = cy + nx * hw;
            g.bx = cx + ny * hw; g.by = cy - nx * hw;
            g.abx = g.bx - g.ax; g.aby = g.by - g.ay;
            g.len2 = g.abx * g.abx + g.aby * g.aby;
        }
    }

    void layoutCourse() {
        double ux = Math.cos(windBase), uy = Math.sin(windBase);
        double qx = -uy, qy = ux;
        double C0x = ux * -78, C0y = uy * -78;
        double Wx = ux * 80, Wy = uy * 80;
        double Gx = ux * -6 + qx * 62, Gy = uy * -6 + qy * 62;

        gates[0].name = "起航线"; gates[0].mark = false;
        gates[1].name = "上风标"; gates[1].mark = true;
        gates[2].name = "三角标"; gates[2].mark = true;
        gates[3].name = "终点线"; gates[3].mark = false;

        mkGate(gates[0], C0x, C0y, ux, uy, 13);

        double dx = Wx - C0x, dy = Wy - C0y, dl = Math.hypot(dx, dy);
        mkGate(gates[1], Wx, Wy, dx / dl, dy / dl, 0);

        dx = Gx - Wx; dy = Gy - Wy; dl = Math.hypot(dx, dy);
        mkGate(gates[2], Gx, Gy, dx / dl, dy / dl, 0);

        mkGate(gates[3], C0x, C0y, -ux, -uy, 13);
    }

    /* ==================== 帆船 ==================== */
    class Racer {
        double x, y, vx, vy;
        double heading = -Math.PI / 2, angVel = 0;
        double rudder = 0, cmdRud = 0, cmdTrim = 0;
        double sailAng = 0, sailVel = 0, speed = 0;
        double trim = 0.7, tackSide = 1;
        double eff = 0, aoa = 0, awaSign = -1;
        int gateIdx = 0;
        boolean inRing = false;
        double prevS = -1;
        boolean lapActive = false;
        int lapNum = 0;
        double lapTime = 0;
        int lapsDone = 0;
        boolean isPlayer = false, ai = false, optimus = false;
        double beat = 0.95, gain = 2.4, evalT = 0, chosenDes = 0;
        Integer apSide = null;
        double stuckT = 0, forceT = 0, wakeAcc = 0;
        String name = "";
        String no = "";
        Color color = Color.WHITE;
    }

    Racer ship = new Racer();
    final Racer[] ais = new Racer[4];

    double trimFor(double aa) {
        double amt = clamp(aa - 0.55, SAIL_MIN + 0.02, SAIL_MAX);
        return clamp(1 - (amt - SAIL_MIN) / (SAIL_MAX - SAIL_MIN), 0, 1);
    }

    double effAoa(double a) {
        if (a < 0.10) return (a / 0.10) * (a / 0.10) * 0.15;
        if (a < 0.45) return (a - 0.10) / 0.35;
        if (a < 0.85) return 1;
        if (a < 1.70) return 1 - 0.62 * (a - 0.85) / 0.85;
        if (a < 2.60) return 0.38 - 0.18 * (a - 1.70) / 0.90;
        return 0.20;
    }

    double sailAmt(Racer r, double aa) {
        double cap = Math.max(aa - 0.06, 0.04);
        return Math.min(SAIL_MIN + (SAIL_MAX - SAIL_MIN) * (1 - r.trim), cap);
    }

    double sailTargetR(Racer r, double awa, double aa) {
        if (Math.abs(awa) > 0.3) r.tackSide = sg(awa);
        return -r.tackSide * sailAmt(r, aa);
    }

    void resetTrim(Racer r, double heading) {
        double awa = norm(heading - windDir);
        r.awaSign = sg(awa);
        r.trim = trimFor(Math.abs(awa));
        r.sailAng = sailTargetR(r, awa, Math.abs(awa));
        r.aoa = Math.max(Math.abs(awa) - Math.abs(r.sailAng), 0);
        r.eff = effAoa(r.aoa);
    }

    void gridStart(Racer r, double lat, double back) {
        double ux = Math.cos(windBase), uy = Math.sin(windBase);
        double qx = -uy, qy = ux;
        Gate L = gates[0];
        r.x = L.cx + qx * lat - ux * back;
        r.y = L.cy + qy * lat - uy * back;
        r.heading = norm(windDir + 0.95);
        r.vx = r.vy = r.angVel = r.sailVel = 0;
        r.rudder = r.cmdRud = r.cmdTrim = 0;
        r.wakeAcc = 0; r.stuckT = 0; r.forceT = 0; r.apSide = null;
        resetTrim(r, r.heading);
        r.gateIdx = 0; r.inRing = false;
        r.prevS = (r.x - L.cx) * L.nx + (r.y - L.cy) * L.ny;
        r.lapActive = false; r.lapNum = 0; r.lapTime = 0; r.lapsDone = 0;
    }

    /* ==================== 船物理 ==================== */
    void stepBoat(Racer r, double dt) {
        r.rudder = lerp(r.rudder, clamp(r.cmdRud, -1, 1), 1 - Math.exp(-10 * dt));
        if (r.cmdTrim > 0) r.trim = clamp(r.trim + 1.6 * dt, 0, 1);
        else if (r.cmdTrim < 0) r.trim = clamp(r.trim - 1.6 * dt, 0, 1);

        double hx = Math.cos(r.heading), hy = Math.sin(r.heading);
        double px = -hy, py = hx;
        double vA = r.vx * hx + r.vy * hy;
        double vL = r.vx * px + r.vy * py;

        double awa = norm(r.heading - windDir), aa = Math.abs(awa);
        if (Math.abs(awa) > 0.02) r.awaSign = awa > 0 ? 1 : -1;

        double wN = windSpd / 10.0 * gustFactor(r.x, r.y);
        double sT = sailTargetR(r, awa, aa);
        r.sailVel += ((sT - r.sailAng) * 40 - r.sailVel * 7.0) * dt;
        r.sailAng += r.sailVel * dt;

        double sailMag = Math.abs(r.sailAng);
        double aoa = Math.max(aa - sailMag, 0);
        double m = ss(EYE0, EYE1, aa);
        double eff = effAoa(aoa) * m;
        r.aoa = aoa; r.eff = eff;

        double drive = eff * wN * 7.5 - (1 - m) * 0.9 * wN;
        if (aoa < 0.07) drive -= 0.5 * wN * m;

        vA += drive * dt;
        vA -= vA * 1.05 * dt;
        if (vA < 0) vA -= vA * 1.8 * dt;

        vL += sg(awa) * eff * wN * 1.6 * dt;
        vL -= vL * 2.8 * dt;

        boolean stern = vA < -0.25;
        r.angVel += r.rudder * 2.6 * (0.55 + Math.min(Math.abs(vA) / 6, 1)) * dt * (stern ? -1 : 1);
        r.angVel -= r.angVel * 3.2 * dt;
        if (aa < 0.35 && Math.abs(vA) < 0.6) r.angVel += r.awaSign * 0.6 * dt;

        r.heading = norm(r.heading + r.angVel * dt);
        r.vx = hx * vA + px * vL;
        r.vy = hy * vA + py * vL;
        r.x += r.vx * dt;
        r.y += r.vy * dt;
        r.speed = Math.hypot(r.vx, r.vy);

        double R = 1.5;
        if (r.x < -BOUND_X + R) { r.x = -BOUND_X + R; r.vx = Math.abs(r.vx) * 0.35; r.vy *= 0.85; }
        if (r.x > BOUND_X - R) { r.x = BOUND_X - R; r.vx = -Math.abs(r.vx) * 0.35; r.vy *= 0.85; }
        if (r.y < -BOUND_Z + R) { r.y = -BOUND_Z + R; r.vy = Math.abs(r.vy) * 0.35; r.vx *= 0.85; }
        if (r.y > BOUND_Z - R) { r.y = BOUND_Z - R; r.vy = -Math.abs(r.vy) * 0.35; r.vx *= 0.85; }

        if (r.speed > 0.6) {
            r.wakeAcc += dt * r.speed * 0.6;
            while (r.wakeAcc > 1) {
                r.wakeAcc--;
                double sx = r.x - hx * 1.1, sy = r.y - hy * 1.1;
                double s2 = Math.random() < 0.5 ? 1 : -1;
                addFoam(sx + px * 0.32 * s2, sy + py * 0.32 * s2,
                        px * 1.2 * s2 + r.vx * 0.1, py * 1.2 * s2 + r.vy * 0.1);
            }
        }
    }

    /* ==================== AI ==================== */
    void autopilotAI(Racer r, double dt) {
        Gate g = gates[r.gateIdx];
        double tx = g.cx + g.nx * (g.mark ? 2 : 6);
        double ty = g.cy + g.ny * (g.mark ? 2 : 6);
        double brg = Math.atan2(ty - r.y, tx - r.x);
        double beat = r.beat, des;

        if (Math.abs(norm(brg - windDir)) < beat) {
            double a = norm(windDir + beat), b = norm(windDir - beat);
            double da = Math.abs(norm(brg - a)), db = Math.abs(norm(brg - b));
            if (r.apSide == null) r.apSide = da < db ? 1 : -1;
            if (r.apSide > 0 && db < da - 0.3) r.apSide = -1;
            else if (r.apSide < 0 && da < db - 0.3) r.apSide = 1;
            des = r.apSide > 0 ? a : b;
        } else des = brg;

        double d = norm(des - r.heading);
        if (r.speed < 1.2) r.stuckT += dt; else r.stuckT = 0;
        if (r.stuckT > 2.5) r.forceT = 2.5;
        if (r.forceT > 0) { r.forceT -= dt; d = 1; }
        r.cmdRud = lerp(r.cmdRud, clamp(d * r.gain, -1, 1), 1 - Math.exp(-8 * dt));

        double awa = norm(r.heading - windDir);
        r.trim = lerp(r.trim, trimFor(Math.abs(awa)), 1 - Math.exp(-2.5 * dt));
    }

    void optimusAI(Racer r, double dt) {
        r.evalT -= dt;
        Gate g = gates[r.gateIdx];
        double tx = g.mark ? g.cx : g.cx + g.nx * 5;
        double ty = g.mark ? g.cy : g.cy + g.ny * 5;
        final double beat = 0.80;

        if (r.evalT <= 0) {
            r.evalT = 0.3;
            final double DT = 0.1;
            final int N = 30;
            double brg = Math.atan2(ty - r.y, tx - r.x);
            double mySide = Math.signum(r.sailAng) == 0 ? 1 : Math.signum(r.sailAng);

            ArrayList<Double> cands = new ArrayList<>();
            cands.add(brg);
            cands.add(norm(windDir + beat)); cands.add(norm(windDir - beat));
            cands.add(norm(windDir + beat + 0.14)); cands.add(norm(windDir - beat - 0.14));
            for (double off : new double[]{0.5, 1.0, 1.6}) {
                cands.add(norm(brg + off)); cands.add(norm(brg - off));
            }

            double bestV = -1e9, bestD = brg;
            for (double des : cands) {
                double sx = r.x, sy = r.y, sp = Math.max(r.speed, 0.8), penalty = 0;
                double dirx = Math.cos(des), diry = Math.sin(des);
                double candSide = norm(des - windDir) >= 0 ? -1 : 1;
                double tackCost = Math.signum(candSide) != Math.signum(mySide) ? 1.1 : 0;

                for (int i = 0; i < N; i++) {
                    double awa = Math.abs(norm(des - windDir));
                    double m = ss(EYE0, EYE1, awa);
                    double aoa = Math.max(awa - sailAmt(r, awa), 0);
                    double eff = effAoa(aoa) * m;
                    double wN = (windSpd / 10.0) * gustFactor(sx, sy);
                    sp += eff * wN * 7.5 * DT;
                    sp -= sp * 1.05 * DT + (sp < 0 ? Math.abs(sp) * 1.8 * DT : 0);
                    sp = clamp(sp, 0, 8);
                    sx += dirx * sp * DT; sy += diry * sp * DT;

                    if (awa < EYE0) penalty += DT * 2.2;
                    else if (awa < EYE1) penalty += DT * 0.25 * (1 - (awa - EYE0) / (EYE1 - EYE0));
                    if (aoa < 0.07) penalty += DT * 0.8;
                    if (Math.abs(sx) > BOUND_X - 4 || Math.abs(sy) > BOUND_Z - 4) penalty += DT * 2.0;
                }
                double prog = (sx - r.x) * (tx - r.x) + (sy - r.y) * (ty - r.y);
                double progLen = Math.hypot(tx - r.x, ty - r.y);
                if (progLen == 0) progLen = 1;
                double val = prog / progLen - tackCost - penalty * 2.5;

                if (g.mark && Math.hypot(tx - r.x, ty - r.y) < 26) {
                    double cross = Math.signum(norm(Math.atan2(ty - r.y, tx - r.x) - des));
                    val += cross * (g.ny > 0 ? 1 : -1) * 0.6;
                }
                if (gustFactor(sx, sy) > 1) val += 0.9;
                if (val > bestV) { bestV = val; bestD = des; }
            }
            r.chosenDes = bestD;
        }

        double d = norm(r.chosenDes - r.heading);
        if (r.speed < 1.2) r.stuckT += dt; else r.stuckT = 0;
        if (r.stuckT > 1.8) r.forceT = 1.8;
        if (r.forceT > 0) { r.forceT -= dt; d = 1; }
        r.cmdRud = lerp(r.cmdRud, clamp(d * 3.2, -1, 1), 1 - Math.exp(-8 * dt));

        double awa = norm(r.heading - windDir), aa = Math.abs(awa);
        double targetAmt = clamp(aa - 0.66, SAIL_MIN + 0.02, SAIL_MAX);
        double targetTrim = clamp(1 - (targetAmt - SAIL_MIN) / (SAIL_MAX - SAIL_MIN), 0, 1);
        r.trim = lerp(r.trim, targetTrim, 1 - Math.exp(-6 * dt));
    }

    void autopilotDemo(double dt) {
        Racer tmp = ship;
        double oldBeat = tmp.beat;
        tmp.beat = 0.95;
        autopilotAI(tmp, dt);
        tmp.beat = oldBeat;
    }

    /* ==================== 门判定 ==================== */
    void checkGatesFor(Racer r) {
        Gate g = gates[r.gateIdx];
        double s = (r.x - g.cx) * g.nx + (r.y - g.cy) * g.ny;
        if (g.mark) {
            double dist = Math.hypot(r.x - g.cx, r.y - g.cy);
            if (!r.inRing) {
                if (dist < RING_R) r.inRing = true;
            } else if (s >= 0) {
                r.inRing = false;
                onGate(r);
            } else if (dist > RING_R + 1) {
                r.inRing = false;
            }
        } else {
            if (r.prevS < 0 && s >= 0) {
                double tt = ((r.x - g.ax) * g.abx + (r.y - g.ay) * g.aby) / g.len2;
                if (tt > -0.05 && tt < 1.05) onGate(r);
            }
            r.prevS = s;
        }
    }

    void onGate(Racer r) {
        int done = r.gateIdx;
        r.gateIdx = (r.gateIdx + 1) % gates.length;
        Gate ng = gates[r.gateIdx];
        r.prevS = (r.x - ng.cx) * ng.nx + (r.y - ng.cy) * ng.ny;
        r.inRing = false;
        if (state != RACE) return;

        if (r.isPlayer) {
            if (done == 0) {
                r.lapActive = true; r.lapNum++; r.lapTime = 0;
                banner(r.lapNum == 1 ? "比赛开始 · 迎风起航" : "第 " + r.lapNum + " 圈 · 起航");
            } else if (done == gates.length - 1) {
                double lt = r.lapTime;
                boolean rec = (best <= 0 || lt < best);
                if (rec) { best = lt; saveBest(); }
                r.lapTime = 0; r.lapActive = false; r.lapsDone++;
                banner("第 " + r.lapNum + " 圈 · " + fmt(lt) + (rec ? " ｜ 新纪录" : ""));
            } else {
                banner(done == 1 ? "已绕上风标 · 侧风段" : "已绕三角标 · 冲线段");
            }
        } else if (r.ai) {
            if (done == 0) { r.lapActive = true; r.lapNum++; r.lapTime = 0; }
            else if (done == gates.length - 1) {
                double lt = r.lapTime;
                r.lapsDone++; r.lapTime = 0; r.lapActive = false;
                toast(r.name + " [" + r.no + "] 完成第 " + r.lapsDone + " 圈 · " + fmt(lt));
            }
        }
    }

    static String fmt(double s) {
        if (s < 0) s = 0;
        int m = (int) (s / 60);
        double r = s - m * 60;
        return m + ":" + (r < 10 ? "0" : "") + String.format(Locale.US, "%.1f", r);
    }

    void rescue() {
        Gate g = gates[ship.gateIdx];
        ship.x = g.cx - g.nx * 11;
        ship.y = g.cy - g.ny * 11;
        ship.vx = ship.vy = ship.angVel = ship.sailVel = 0;
        double brg = Math.atan2(g.ny, g.nx);
        double rel = norm(brg - windDir);
        ship.heading = Math.abs(rel) < 0.95 ? norm(windDir + (rel >= 0 ? 0.95 : -0.95)) : brg;
        resetTrim(ship, ship.heading);
        ship.prevS = (ship.x - g.cx) * g.nx + (ship.y - g.cy) * g.ny;
        ship.inRing = false;
        toast("已送回航线上");
    }

    /* ==================== 泡沫 & 风纹 ==================== */
    static class Foam { double x, y, vx, vy, t, life, r; boolean on; }
    final Foam[] foam = new Foam[400];
    int foamI = 0;

    void addFoam(double x, double y, double vx, double vy) {
        Foam f = foam[foamI];
        foamI = (foamI + 1) % foam.length;
        f.on = true; f.x = x; f.y = y; f.vx = vx; f.vy = vy;
        f.t = 0; f.life = 1.0 + Math.random() * 1.2; f.r = 0.05 + Math.random() * 0.08;
    }

    void updateFoam(double dt) {
        for (Foam f : foam) {
            if (!f.on) continue;
            f.t += dt; f.x += f.vx * dt; f.y += f.vy * dt;
            f.vx *= 0.95; f.vy *= 0.95;
            if (f.t > f.life) f.on = false;
        }
    }

    static class Ripple { double x, y, ph, sp, len; }
    final Ripple[] ripples = new Ripple[160];

    void resetRipple(Ripple r) {
        r.x = -140 + Math.random() * 280;
        r.y = -100 + Math.random() * 200;
        r.ph = -Math.random() * 1.2;
        r.sp = 0.6 + Math.random() * 0.8;
        r.len = 1.5 + Math.random() * 2.5;
    }

    void updateRipples(double dt) {
        double ux = Math.cos(windDir), uy = Math.sin(windDir);
        for (Ripple r : ripples) {
            r.ph += dt * 0.42;
            r.x -= ux * windSpd * 0.85 * r.sp * dt;
            r.y -= uy * windSpd * 0.85 * r.sp * dt;
            if (r.ph > 1 || Math.abs(r.x) > 150 || Math.abs(r.y) > 110) resetRipple(r);
        }
    }

    /* ==================== 状态 ==================== */
    int state = MENU;
    boolean paused = false;
    double cd = 0;
    double t = 0;
    long epochNano = System.nanoTime();
    long lastNano = System.nanoTime();
    String bannerTxt = "";
    double bannerT = 0;
    String toastTxt = "";
    double toastT = 0;
    double best = -1;

    void banner(String s) { bannerTxt = s; bannerT = 2.8; }
    void toast(String s) { toastTxt = s; toastT = 2.6; }

    /* ==================== 相机 ==================== */
    double camX = 0, camY = 0, zoom = 12;
    double x2s(double wx) { return (wx - camX) * zoom + getWidth() / 2.0; }
    double z2s(double wz) { return (wz - camY) * zoom + getHeight() / 2.0; }

    /* ==================== 输入 ==================== */
    boolean kL, kR, kUp, kDown;

    /* ==================== 字体 ==================== */
    static Font uiFont(int style, int size) {
        try {
            String[] names = { "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC",
                    "Source Han Sans SC", "WenQuanYi Micro Hei", "SimHei", "Dialog" };
            Set<String> avail = new HashSet<>(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for (String n : names) if (avail.contains(n)) return new Font(n, style, size);
        } catch (Throwable ignore) { }
        return new Font(Font.SANS_SERIF, style, size);
    }

    final Font fSmall = uiFont(Font.PLAIN, 11);
    final Font fNorm = uiFont(Font.PLAIN, 13);
    final Font fBold = uiFont(Font.BOLD, 14);
    final Font fBig = uiFont(Font.BOLD, 46);
    final Font fHuge = uiFont(Font.BOLD, 96);

    /* ==================== 应用图标（程序化帆船） ==================== */
    /** 用代码画一个帆船图标，size 为正方形边长 */
    static Image makeAppIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        double s = size / 64.0; // 以 64px 为设计基准等比缩放

        // 深色圆角底
        g.setColor(new Color(0x0d3a42));
        g.fill(new RoundRectangle2D.Double(2 * s, 2 * s, 60 * s, 60 * s, 14 * s, 14 * s));

        // 水面线
        g.setColor(new Color(0x1f6b74));
        g.fill(new Rectangle2D.Double(6 * s, 46 * s, 52 * s, 3 * s));

        // 桅杆
        g.setColor(new Color(0xece3cf));
        g.setStroke(new BasicStroke((float) (2.2 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(30 * s, 12 * s, 30 * s, 46 * s));

        // 主帆（右侧大三角）
        Path2D mainSail = new Path2D.Double();
        mainSail.moveTo(30 * s, 13 * s);
        mainSail.quadTo(46 * s, 28 * s, 50 * s, 44 * s);
        mainSail.lineTo(30 * s, 44 * s);
        mainSail.closePath();
        g.setColor(new Color(0xf5f2ea));
        g.fill(mainSail);

        // 前帆（左侧小三角，橙色）
        Path2D jib = new Path2D.Double();
        jib.moveTo(30 * s, 18 * s);
        jib.quadTo(18 * s, 30 * s, 13 * s, 44 * s);
        jib.lineTo(30 * s, 44 * s);
        jib.closePath();
        g.setColor(new Color(0xff7a3d));
        g.fill(jib);

        // 船体
        Path2D hull = new Path2D.Double();
        hull.moveTo(10 * s, 47 * s);
        hull.lineTo(54 * s, 47 * s);
        hull.quadTo(48 * s, 56 * s, 32 * s, 56 * s);
        hull.quadTo(16 * s, 56 * s, 10 * s, 47 * s);
        hull.closePath();
        g.setColor(new Color(0xf2ead8));
        g.fill(hull);
        g.setColor(new Color(0x7a2f10));
        g.setStroke(new BasicStroke((float) (1.2 * s)));
        g.draw(hull);

        g.dispose();
        return img;
    }

    /* ==================== 构造 ==================== */
    public LakeRegatta() {
        setBackground(new Color(0x08181c));
        setOpaque(true);
        setFocusable(true);
        layoutCourse();

        for (int i = 0; i < foam.length; i++) foam[i] = new Foam();
        for (int i = 0; i < ripples.length; i++) { ripples[i] = new Ripple(); resetRipple(ripples[i]); }

        ship.isPlayer = true;
        ship.name = "你";
        ship.no = "7";
        ship.color = new Color(0xff7a3d);

        ais[0] = mkAI("AI·玄羽", "08", new Color(0x7fd8c8), 0.90, 2.25, false);
        ais[1] = mkAI("AI·雪浪", "21", new Color(0xe8c98a), 1.00, 2.55, false);
        ais[2] = mkAI("AI·青崖", "63", new Color(0x9db8e8), 0.80, 2.85, true);
        ais[3] = mkAI("AI·白鸥", "27", new Color(0xd8cfe8), 1.06, 2.10, false);

        loadBest();
        gridStart(ship, 0, 14);
        resetAIStart();

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_LEFT: case KeyEvent.VK_A: kL = true; break;
                    case KeyEvent.VK_RIGHT: case KeyEvent.VK_D: kR = true; break;
                    case KeyEvent.VK_UP: case KeyEvent.VK_W: kUp = true; break;
                    case KeyEvent.VK_DOWN: case KeyEvent.VK_S: kDown = true; break;
                    case KeyEvent.VK_R: if (state == RACE && !paused) rescue(); break;
                    case KeyEvent.VK_P: case KeyEvent.VK_ESCAPE: if (state == RACE) paused = !paused; break;
                    case KeyEvent.VK_SPACE:
                        if (state == MENU) beginRace();
                        else if (state == RACE) paused = !paused;
                        break;
                }
            }
            @Override public void keyReleased(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_LEFT: case KeyEvent.VK_A: kL = false; break;
                    case KeyEvent.VK_RIGHT: case KeyEvent.VK_D: kR = false; break;
                    case KeyEvent.VK_UP: case KeyEvent.VK_W: kUp = false; break;
                    case KeyEvent.VK_DOWN: case KeyEvent.VK_S: kDown = false; break;
                }
            }
        });

        addMouseWheelListener(e -> zoom = clamp(zoom * Math.exp(-e.getPreciseWheelRotation() * 0.1), 4, 40));
    }

    Racer mkAI(String name, String no, Color c, double beat, double gain, boolean optimus) {
        Racer r = new Racer();
        r.name = name; r.no = no; r.color = c;
        r.ai = true; r.beat = beat; r.gain = gain; r.optimus = optimus;
        return r;
    }

    void resetAIStart() {
        gridStart(ais[0], -11, 17);
        gridStart(ais[1], 11, 17);
        gridStart(ais[2], -5.5, 15.5);
        gridStart(ais[3], 5.5, 15.5);
    }

    /* ==================== 最佳成绩 ==================== */
    File bestFile() { return new File(System.getProperty("user.home"), ".hysf_best.txt"); }

    void loadBest() {
        try {
            if (bestFile().exists()) {
                String s = new String(java.nio.file.Files.readAllBytes(bestFile().toPath())).trim();
                best = Double.parseDouble(s);
            }
        } catch (Exception ignored) { }
    }

    void saveBest() {
        try {
            java.nio.file.Files.write(bestFile().toPath(),
                    String.valueOf(best).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    /* ==================== 流程 ==================== */
    void beginRace() {
        gridStart(ship, 0, 14);
        resetAIStart();
        state = COUNT;
        cd = 3.4;
        paused = false;
        zoom = 12;
        camX = ship.x; camY = ship.y;
    }

    @Override public void actionPerformed(ActionEvent e) {
        long now = System.nanoTime();
        double dt = Math.min(0.033, (now - lastNano) / 1e9);
        lastNano = now;
        update(dt);
        repaint();
    }

    void update(double dt) {
        t = (System.nanoTime() - epochNano) / 1e9;
        updateWind(t);

        if (!paused) {
            if (state == MENU) {
                autopilotDemo(dt);
                stepBoat(ship, dt);
                checkGatesFor(ship);
            } else if (state == COUNT) {
                cd -= dt;
                if (cd <= 0) state = RACE;
            } else {
                ship.lapTime += dt;
                ship.cmdRud = (kL ? -1 : 0) + (kR ? 1 : 0);
                ship.cmdTrim = (kUp ? 1 : 0) - (kDown ? 1 : 0);
                stepBoat(ship, dt);
                checkGatesFor(ship);
                for (Racer r : ais) {
                    r.lapTime += dt;
                    if (r.optimus) optimusAI(r, dt); else autopilotAI(r, dt);
                    stepBoat(r, dt);
                }
                separateBoats();
                for (Racer r : ais) checkGatesFor(r);
            }
            updateFoam(dt);
            updateRipples(dt);
        }
        if (bannerT > 0) bannerT -= dt;
        if (toastT > 0) toastT -= dt;
        double k = 1 - Math.exp(-5 * dt);
        camX = lerp(camX, ship.x, k);
        camY = lerp(camY, ship.y, k);
    }

    void separateBoats() {
        Racer[] list = new Racer[5];
        list[0] = ship;
        System.arraycopy(ais, 0, list, 1, 4);
        for (int i = 0; i < list.length; i++) {
            for (int j = i + 1; j < list.length; j++) {
                Racer a = list[i], b = list[j];
                double dx = b.x - a.x, dy = b.y - a.y, d = Math.hypot(dx, dy);
                if (d > 0.01 && d < 2.2) {
                    double nx = dx / d, ny = dy / d, ov = (2.2 - d) / 2;
                    a.x -= nx * ov; a.y -= ny * ov;
                    b.x += nx * ov; b.y += ny * ov;
                    double rv = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
                    if (rv < 0) {
                        a.vx += nx * rv * 0.5; a.vy += ny * rv * 0.5;
                        b.vx -= nx * rv * 0.5; b.vy -= ny * rv * 0.5;
                    }
                }
            }
        }
    }

    /* ==================== 渲染 ==================== */
    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int W = getWidth(), H = getHeight();

        g.setColor(new Color(0x0d3a42));
        g.fillRect(0, 0, W, H);

        try {
            drawGustBand(g);
            drawRipples(g);
            drawBanks(g);
            drawCourse(g);
            drawFoam(g);
            drawBoats(g);
        } catch (Throwable ex) { reportPaintError(g, "WORLD", ex); }

        try {
            drawHUD(g, W, H);
        } catch (Throwable ex) { reportPaintError(g, "HUD", ex); }

        try {
            if (paused) {
                g.setColor(new Color(4, 14, 16, 150));
                g.fillRect(0, 0, W, H);
                g.setFont(fBig);
                g.setColor(new Color(0xece3cf));
                String s = "已 暂 停";
                FontMetrics fm = g.getFontMetrics();
                g.drawString(s, (W - fm.stringWidth(s)) / 2, H / 2);
            }
            if (state == MENU) drawMenu(g, W, H);
            if (state == COUNT) drawCountdown(g, W, H);
        } catch (Throwable ex) { reportPaintError(g, "OVERLAY", ex); }
    }

    private void reportPaintError(Graphics2D g, String tag, Throwable ex) {
        ex.printStackTrace();
        g.setColor(new Color(0, 0, 0, 200));
        g.fillRect(0, 0, getWidth(), 48);
        g.setColor(new Color(255, 100, 100));
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        String msg = tag + " 绘制异常: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
        g.drawString(msg, 10, 20);
        g.drawString("→ 完整堆栈见控制台 (stderr)", 10, 40);
    }

    void drawGustBand(Graphics2D g) {
        if (!gustOn) return;
        double ux = Math.cos(windDir), uy = Math.sin(windDir);
        double qx = -uy, qy = ux;
        double p0 = gustD, p1 = gustD + gustW;
        double L = 2000;
        Path2D path = new Path2D.Double();
        double x0 = ux * p0 + qx * (-L), y0 = uy * p0 + qy * (-L);
        double x1 = ux * p0 + qx * L, y1 = uy * p0 + qy * L;
        double x2 = ux * p1 + qx * L, y2 = uy * p1 + qy * L;
        double x3 = ux * p1 + qx * (-L), y3 = uy * p1 + qy * (-L);
        path.moveTo(x2s(x0), z2s(y0));
        path.lineTo(x2s(x1), z2s(y1));
        path.lineTo(x2s(x2), z2s(y2));
        path.lineTo(x2s(x3), z2s(y3));
        path.closePath();
        g.setColor(new Color(140, 240, 220, 22));
        g.fill(path);
    }

    void drawRipples(Graphics2D g) {
        double ux = Math.cos(windDir), uy = Math.sin(windDir);
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Ripple r : ripples) {
            if (r.ph <= 0 || r.ph >= 1) continue;
            double fade = Math.min(1, Math.min(r.ph * 3, (1 - r.ph) * 3));
            boolean hot = gustFactor(r.x, r.y) > 1;
            int a = (int) (fade * (hot ? 120 : 34));
            if (a <= 1) continue;
            g.setColor(new Color(210, 245, 240, a));
            double lx = ux * r.len, ly = uy * r.len;
            g.draw(new Line2D.Double(
                    x2s(r.x - lx * 0.5), z2s(r.y - ly * 0.5),
                    x2s(r.x + lx * 0.5), z2s(r.y + ly * 0.5)));
        }
    }

    void drawBanks(Graphics2D g) {
        g.setColor(new Color(0x26381e));
        double l = x2s(-BOUND_X), rr = x2s(BOUND_X);
        double tp = z2s(-BOUND_Z), bt = z2s(BOUND_Z);
        if (l > 0) g.fillRect(0, 0, (int) l, getHeight());
        if (rr < getWidth()) g.fillRect((int) rr, 0, getWidth() - (int) rr, getHeight());
        if (tp > 0) g.fillRect(0, 0, getWidth(), (int) tp);
        if (bt < getHeight()) g.fillRect(0, (int) bt, getWidth(), getHeight() - (int) bt);
        g.setColor(new Color(0x4a3826));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new Rectangle2D.Double(x2s(-BOUND_X), z2s(-BOUND_Z),
                BOUND_X * 2 * zoom, BOUND_Z * 2 * zoom));
    }

    void drawCourse(Graphics2D g) {
        g.setColor(new Color(255, 122, 61, 70));
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1f, new float[]{6f, 6f}, 0f));
        Path2D line = new Path2D.Double();
        line.moveTo(x2s(gates[0].cx), z2s(gates[0].cy));
        line.lineTo(x2s(gates[1].cx), z2s(gates[1].cy));
        line.lineTo(x2s(gates[2].cx), z2s(gates[2].cy));
        line.closePath();
        g.draw(line);
        g.setStroke(new BasicStroke(1.4f));

        for (int i = 0; i < gates.length; i++) {
            Gate gt = gates[i];
            boolean cur = (i == ship.gateIdx);
            if (gt.mark) {
                double pulse = cur ? 0.5 + 0.4 * Math.sin(t * 4.5) : 0.18;
                g.setColor(new Color(255, 138, 61, (int) (255 * pulse)));
                g.setStroke(new BasicStroke(cur ? 2.6f : 1.6f));
                double rr = RING_R * zoom;
                g.draw(new Ellipse2D.Double(x2s(gt.cx) - rr, z2s(gt.cy) - rr, rr * 2, rr * 2));
            } else {
                double pulse = cur ? 0.45 + 0.4 * Math.sin(t * 4.5) : 0.2;
                g.setColor(new Color(255, 154, 77, (int) (255 * pulse)));
                g.setStroke(new BasicStroke(cur ? 4f : 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                        1f, new float[]{10f, 8f}, 0f));
                g.draw(new Line2D.Double(x2s(gt.ax), z2s(gt.ay), x2s(gt.bx), z2s(gt.by)));
                g.setStroke(new BasicStroke(1.4f));
            }
        }

        for (int i = 0; i < gates.length; i++) {
            Gate gt = gates[i];
            if (gt.mark) {
                drawBuoy(g, gt.cx, gt.cy, i == ship.gateIdx);
            } else if (i == 0) {
                drawBuoy(g, gt.ax, gt.ay, false);
                drawBuoy(g, gt.bx, gt.by, false);
            }
        }
    }

    void drawBuoy(Graphics2D g, double wx, double wy, boolean hot) {
        double sx = x2s(wx), sy = z2s(wy);
        double bob = Math.sin(t * 1.6 + wx * 0.2) * zoom * 0.06;
        sy += bob;
        double r = Math.max(4.5, 0.55 * zoom);
        g.setColor(new Color(0x7a2f10));
        g.fill(new Ellipse2D.Double(sx - r * 0.9, sy + r * 0.4, r * 1.8, r * 0.7));
        g.setColor(hot ? new Color(0xffb14a) : new Color(0xe85a1f));
        Path2D cone = new Path2D.Double();
        cone.moveTo(sx, sy - r * 1.5);
        cone.lineTo(sx + r * 0.85, sy + r * 0.6);
        cone.lineTo(sx - r * 0.85, sy + r * 0.6);
        cone.closePath();
        g.fill(cone);
        if (hot) {
            g.setColor(new Color(255, 217, 160, 160));
            double lr = r * (1.6 + 0.4 * Math.sin(t * 6));
            g.fill(new Ellipse2D.Double(sx - lr / 2, sy - r * 1.7 - lr / 2, lr, lr));
        }
    }

    void drawFoam(Graphics2D g) {
        for (Foam f : foam) {
            if (!f.on) continue;
            double k = 1 - f.t / f.life;
            if (k <= 0) continue;
            double rr = Math.max(1.2, f.r * zoom * (1 + f.t * 1.6));
            int a = (int) (k * 150);
            g.setColor(new Color(238, 248, 244, a));
            g.fill(new Ellipse2D.Double(x2s(f.x) - rr / 2, z2s(f.y) - rr / 2, rr, rr));
        }
    }

    void drawBoats(Graphics2D g) {
        drawBoat(g, ship);
        for (Racer r : ais) drawBoat(g, r);
    }

    void drawBoat(Graphics2D g, Racer r) {
        double sx = x2s(r.x), sy = z2s(r.y);
        double bobY = Math.sin(t * 1.9 + r.x * 0.1) * zoom * 0.06;
        sy += bobY;

        AffineTransform old = g.getTransform();
        g.translate(sx, sy);
        g.rotate(r.heading);

        double z = zoom;

        Path2D hull = new Path2D.Double();
        hull.moveTo(1.30 * z, 0);
        hull.quadTo(0.60 * z, 0.30 * z, -0.20 * z, 0.32 * z);
        hull.quadTo(-1.00 * z, 0.32 * z, -1.15 * z, 0.18 * z);
        hull.lineTo(-1.15 * z, -0.18 * z);
        hull.quadTo(-1.00 * z, -0.32 * z, -0.20 * z, -0.32 * z);
        hull.quadTo(0.60 * z, -0.30 * z, 1.30 * z, 0);
        hull.closePath();

        g.setColor(r.isPlayer ? new Color(0xf2ead8) : new Color(0x59636b));
        g.fill(hull);
        g.setColor(new Color(0, 0, 0, 110));
        g.setStroke(new BasicStroke(1.1f));
        g.draw(hull);

        double mastX = 0.18 * z;
        double sailLen = 1.30 * z;
        double ang = Math.PI - r.sailAng;
        double ex = mastX + Math.cos(ang) * sailLen;
        double ey = Math.sin(ang) * sailLen;

        double belly = (0.10 + r.eff * 0.45) * z * sg(r.sailAng);
        Path2D sail = new Path2D.Double();
        sail.moveTo(mastX, 0);
        sail.quadTo((mastX + ex) / 2 + belly, (ey) / 2 + belly * 0.6, ex, ey);
        sail.lineTo(mastX, 0);
        sail.closePath();

        g.setColor(new Color(245, 242, 234, 235));
        g.fill(sail);
        g.setColor(new Color(90, 80, 70, 160));
        g.setStroke(new BasicStroke(1.0f));
        g.draw(sail);

        g.setColor(new Color(0x4a4238));
        g.fill(new Ellipse2D.Double(mastX - 1.5, -1.5, 3, 3));

        if (r.no != null && !r.no.isEmpty() && r.isPlayer) {
            g.rotate(-r.heading);
            g.setFont(fSmall);
            g.setColor(new Color(236, 227, 207, 220));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(r.no, -fm.stringWidth(r.no) / 2, (int) (-0.9 * z - 4));
        }
        g.setTransform(old);
    }

    /* ==================== HUD ==================== */
    void drawHUD(Graphics2D g, int W, int H) {
        int ccx = 68, ccy = 68, ccr = 40;
        g.setColor(new Color(7, 20, 23, 170));
        g.fill(new RoundRectangle2D.Double(16, 16, 104, 104, 12, 12));
        g.setColor(new Color(236, 227, 207, 40));
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(16, 16, 104, 104, 12, 12));

        g.setColor(new Color(236, 227, 207, 46));
        g.draw(new Ellipse2D.Double(ccx - ccr, ccy - ccr, ccr * 2, ccr * 2));
        for (int i = 0; i < 12; i++) {
            double a = i * TAU / 12;
            g.draw(new Line2D.Double(ccx + Math.cos(a) * (ccr - 5), ccy + Math.sin(a) * (ccr - 5),
                    ccx + Math.cos(a) * ccr, ccy + Math.sin(a) * ccr));
        }

        g.setColor(new Color(226, 74, 54, 90));
        int start = (int) Math.toDegrees(-(windDir + EYE1));
        int ext = (int) Math.toDegrees(2 * EYE1);
        g.fillArc(ccx - ccr + 4, ccy - ccr + 4, (ccr - 4) * 2, (ccr - 4) * 2, start, ext);
        g.setColor(new Color(226, 74, 54, 220));
        g.setStroke(new BasicStroke(3f));
        int s0 = (int) Math.toDegrees(-(windDir + EYE0));
        int e0 = (int) Math.toDegrees(2 * EYE0);
        g.drawArc(ccx - ccr + 4, ccy - ccr + 4, (ccr - 4) * 2, (ccr - 4) * 2, s0, e0);

        g.setColor(new Color(0xff7a3d));
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double wx = ccx + Math.cos(windDir) * 30, wy = ccy + Math.sin(windDir) * 30;
        g.draw(new Line2D.Double(ccx - Math.cos(windDir) * 22, ccy - Math.sin(windDir) * 22, wx, wy));
        Path2D arrow = new Path2D.Double();
        double ah = windDir + Math.PI / 2;
        arrow.moveTo(wx + Math.cos(windDir) * 8, wy + Math.sin(windDir) * 8);
        arrow.lineTo(wx + Math.cos(ah) * 5, wy + Math.sin(ah) * 5);
        arrow.lineTo(wx - Math.cos(ah) * 5, wy - Math.sin(ah) * 5);
        arrow.closePath();
        g.fill(arrow);

        g.setColor(new Color(0x7fd8c8));
        double hx = ccx + Math.cos(ship.heading) * 24, hy = ccy + Math.sin(ship.heading) * 24;
        double ha = ship.heading + Math.PI / 2;
        Path2D head = new Path2D.Double();
        head.moveTo(hx + Math.cos(ship.heading) * 7, hy + Math.sin(ship.heading) * 7);
        head.lineTo(hx + Math.cos(ha) * 5, hy + Math.sin(ha) * 5);
        head.lineTo(hx - Math.cos(ha) * 5, hy - Math.sin(ha) * 5);
        head.closePath();
        g.fill(head);

        g.setFont(fSmall);
        g.setColor(new Color(236, 227, 207, 190));
        String[] DIRS = { "东", "东南", "南", "西南", "西", "西北", "北", "东北" };
        int di = ((int) Math.round(norm(windDir) / (Math.PI / 4)) % 8 + 8) % 8;
        boolean inG = gustFactor(ship.x, ship.y) > 1;
        String wt = DIRS[di] + "风 " + String.format(Locale.US, "%.1f", windSpd * (inG ? 1.38 : 1) * 1.35) + "kn";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(wt, ccx - fm.stringWidth(wt) / 2, 108);

        int mw = 152, mh = 114;
        int mx0 = W - mw - 20, my0 = 16;
        g.setColor(new Color(7, 20, 23, 170));
        g.fill(new RoundRectangle2D.Double(mx0 - 8, my0 - 8, mw + 16, mh + 96, 12, 12));
        g.setColor(new Color(236, 227, 207, 40));
        g.draw(new RoundRectangle2D.Double(mx0 - 8, my0 - 8, mw + 16, mh + 96, 12, 12));

        double msx = (double) mw / 304.0, msy = (double) mh / 228.0;
        g.setColor(new Color(38, 54, 31));
        g.fillRect(mx0, my0, mw, mh);
        int wx0 = mx0 + (int) ((-BOUND_X + 152) * msx);
        int wy0 = my0 + (int) ((-BOUND_Z + 114) * msy);
        g.setColor(new Color(0x0d3439));
        g.fillRect(wx0, wy0, (int) (BOUND_X * 2 * msx), (int) (BOUND_Z * 2 * msy));

        g.setColor(new Color(255, 122, 61, 90));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1f, new float[]{3f, 3f}, 0f));
        Path2D ml = new Path2D.Double();
        ml.moveTo(mx0 + (gates[0].cx + 152) * msx, my0 + (gates[0].cy + 114) * msy);
        ml.lineTo(mx0 + (gates[1].cx + 152) * msx, my0 + (gates[1].cy + 114) * msy);
        ml.lineTo(mx0 + (gates[2].cx + 152) * msx, my0 + (gates[2].cy + 114) * msy);
        ml.closePath();
        g.draw(ml);
        g.setStroke(new BasicStroke(1f));

        Gate cg = gates[ship.gateIdx];
        g.setColor(new Color(0xff7a3d));
        double gx = mx0 + (cg.cx + 152) * msx, gy = my0 + (cg.cy + 114) * msy;
        if (cg.mark) {
            g.draw(new Ellipse2D.Double(gx - RING_R * msx, gy - RING_R * msy,
                    RING_R * 2 * msx, RING_R * 2 * msy));
        } else {
            g.draw(new Line2D.Double(
                    mx0 + (cg.ax + 152) * msx, my0 + (cg.ay + 114) * msy,
                    mx0 + (cg.bx + 152) * msx, my0 + (cg.by + 114) * msy));
        }

        for (Racer r : allRacers()) {
            double px = mx0 + (r.x + 152) * msx, py = my0 + (r.y + 114) * msy;
            g.setColor(r.isPlayer ? new Color(0xf2ead8) : r.color);
            double rr = r.isPlayer ? 2.6 : 2.0;
            g.fill(new Ellipse2D.Double(px - rr, py - rr, rr * 2, rr * 2));
        }

        g.setFont(fNorm);
        FontMetrics fm2 = g.getFontMetrics();
        int ty = my0 + mh + 26;
        g.setColor(new Color(236, 227, 207, 140));
        g.drawString(ship.lapActive ? ("第 " + ship.lapNum + " 圈") : (ship.lapNum > 0 ? "驶向起点" : "热身"), mx0, ty);
        g.setColor(new Color(0xff7a3d));
        String lt = fmt(ship.lapTime);
        g.drawString(lt, mx0 + mw - fm2.stringWidth(lt), ty);

        g.setColor(new Color(236, 227, 207, 140));
        g.drawString("总计", mx0, ty + 20);
        g.setColor(new Color(236, 227, 207));
        String tt = fmt(ship.lapTime);
        g.drawString(tt, mx0 + mw - fm2.stringWidth(tt), ty + 20);

        g.setColor(new Color(236, 227, 207, 140));
        g.drawString("最佳", mx0, ty + 40);
        g.setColor(new Color(236, 227, 207));
        String bt = best > 0 ? fmt(best) : "--";
        g.drawString(bt, mx0 + mw - fm2.stringWidth(bt), ty + 40);

        int bx = 20, by = H - 76;
        g.setColor(new Color(7, 20, 23, 170));
        g.fill(new RoundRectangle2D.Double(bx - 4, by - 12, 200, 68, 10, 10));
        g.setColor(new Color(236, 227, 207, 40));
        g.draw(new RoundRectangle2D.Double(bx - 4, by - 12, 200, 68, 10, 10));

        g.setFont(fSmall);
        g.setColor(new Color(236, 227, 207, 150));
        g.drawString("缭绳", bx, by + 4);
        int tw = 120, th = 7;
        g.setColor(new Color(236, 227, 207, 70));
        g.draw(new Rectangle2D.Double(bx + 34, by - 4, tw, th));
        g.setColor(new Color(0xff9a4d));
        g.fill(new Rectangle2D.Double(bx + 34, by - 4, tw * ship.trim, th));
        g.setColor(new Color(236, 227, 207));
        g.drawString(Math.round(ship.trim * 100) + "%", bx + 34 + tw + 8, by + 4);

        g.setColor(new Color(236, 227, 207, 70));
        g.draw(new Rectangle2D.Double(bx, by + 18, 180, th));
        double spd = Math.min(1, ship.speed / 7);
        g.setColor(new Color(0x7fd8c8));
        g.fill(new Rectangle2D.Double(bx, by + 18, 180 * spd, th));
        g.setColor(new Color(236, 227, 207, 200));
        g.drawString(String.format(Locale.US, "%.1f kn", ship.speed * 1.94), bx, by + 44);

        int dx = W - 160, dy = H - 150;
        g.setColor(new Color(7, 20, 23, 170));
        g.fill(new RoundRectangle2D.Double(dx - 8, dy - 8, 148, 122, 12, 12));
        g.setColor(new Color(236, 227, 207, 40));
        g.draw(new RoundRectangle2D.Double(dx - 8, dy - 8, 148, 122, 12, 12));

        double eff = ship.eff;
        int pct = (int) Math.round(eff * 100);
        g.setFont(uiFont(Font.BOLD, 26));
        g.setColor(new Color(0x7fd8c8));
        g.drawString(pct + "%", dx + 8, dy + 40);

        g.setFont(fSmall);
        double aa2 = Math.abs(norm(ship.heading - windDir));
        String word; Color wcol;
        if (aa2 < EYE0) { word = "顶风失速"; wcol = new Color(0xe2503c); }
        else if (ship.aoa < LUFF) { word = "太松 · 收帆"; wcol = new Color(0xe2503c); }
        else if (aa2 < EYE1) { word = "脱离风眼中"; wcol = new Color(0xf0aa40); }
        else if (eff > 0.92) { word = "完美受力"; wcol = new Color(0x8fd8a0); }
        else if (eff > 0.55) { word = "良好"; wcol = new Color(0xd9d2c0); }
        else { word = "太紧 · 松帆"; wcol = new Color(0xf0aa40); }
        g.setColor(wcol);
        g.drawString(word, dx + 8, dy + 60);

        g.setColor(new Color(236, 227, 207, 150));
        g.drawString("帆 " + Math.round(Math.abs(ship.sailAng) * 57.3) + "°  攻 " +
                Math.round(ship.aoa * 57.3) + "°", dx + 8, dy + 80);

        Gate g2 = gates[ship.gateIdx];
        String obj = g2.name + (g2.mark && ship.inRing ? " · 已锁定" : "");
        String dist = Math.round(Math.hypot(g2.cx - ship.x, g2.cy - ship.y)) + "m";
        g.setFont(fNorm);
        FontMetrics fm3 = g.getFontMetrics();
        int ow = fm3.stringWidth(obj) + fm3.stringWidth(dist) + 40;
        int ox = (W - ow) / 2;
        g.setColor(new Color(7, 20, 23, 170));
        g.fill(new RoundRectangle2D.Double(ox, 16, ow, 30, 15, 15));
        g.setColor(new Color(236, 227, 207, 40));
        g.draw(new RoundRectangle2D.Double(ox, 16, ow, 30, 15, 15));
        g.setColor(new Color(0xece3cf));
        g.drawString(obj, ox + 14, 36);
        g.setColor(new Color(236, 227, 207, 140));
        g.drawString(dist, ox + 14 + fm3.stringWidth(obj) + 10, 36);

        if (inG && state == RACE) {
            g.setFont(uiFont(Font.BOLD, 13));
            g.setColor(new Color(255, 179, 122, (int) (140 + 100 * Math.sin(t * 6))));
            String s = "阵 风";
            FontMetrics fm4 = g.getFontMetrics();
            g.drawString(s, (W - fm4.stringWidth(s)) / 2, 74);
        }

        if (bannerT > 0) {
            g.setFont(uiFont(Font.BOLD, 18));
            FontMetrics fm5 = g.getFontMetrics();
            int bw = fm5.stringWidth(bannerTxt) + 52;
            int bx2 = (W - bw) / 2;
            int alpha = (int) (255 * Math.min(1, bannerT * 2));
            g.setColor(new Color(7, 20, 23, (int) (alpha * 0.78)));
            g.fill(new RoundRectangle2D.Double(bx2, 96, bw, 40, 8, 8));
            g.setColor(new Color(255, 122, 61, alpha));
            g.setStroke(new BasicStroke(1.4f));
            g.draw(new RoundRectangle2D.Double(bx2, 96, bw, 40, 8, 8));
            g.setColor(new Color(236, 227, 207, alpha));
            g.drawString(bannerTxt, bx2 + 26, 122);
        }

        if (toastT > 0) {
            g.setFont(fNorm);
            FontMetrics fm6 = g.getFontMetrics();
            int alpha = (int) (200 * Math.min(1, toastT * 2));
            g.setColor(new Color(236, 227, 207, alpha));
            g.drawString(toastTxt, (W - fm6.stringWidth(toastTxt)) / 2, H - 110);
        }
    }

    java.util.List<Racer> allRacers() {
        ArrayList<Racer> l = new ArrayList<>();
        l.add(ship);
        l.addAll(Arrays.asList(ais));
        return l;
    }

    void drawCountdown(Graphics2D g, int W, int H) {
        int n = (int) Math.ceil(cd);
        String s = n > 0 ? String.valueOf(n) : "起航";
        g.setFont(fHuge);
        FontMetrics fm = g.getFontMetrics();
        double frac = cd - Math.floor(cd);
        int alpha = (int) (255 * clamp(frac * 3, 0, 1));
        g.setColor(new Color(255, 122, 61, alpha));
        g.drawString(s, (W - fm.stringWidth(s)) / 2, H / 2 + fm.getAscent() / 2 - 40);
    }

    void drawMenu(Graphics2D g, int W, int H) {
        g.setColor(new Color(4, 14, 16, 190));
        g.fillRect(0, 0, W, H);

        g.setFont(uiFont(Font.BOLD, 12));
        g.setColor(new Color(0x7fd8c8));
        g.drawString("REMOTE-CONTROLLED SAILING · JAVA EDITION", 70, 120);

        g.setFont(uiFont(Font.BOLD, 72));
        g.setColor(new Color(0xece3cf));
        g.drawString("湖上遥帆", 66, 200);

        g.setFont(fNorm);
        g.setColor(new Color(236, 227, 207, 140));
        g.drawString("白帆弧号 · 人机同场", 70, 236);

        g.setFont(fNorm);
        g.setColor(new Color(236, 227, 207, 200));
        String[] lines = {
                "← →  或  A D     掌舵",
                "↑ ↓  或  W S     收帆 / 松帆",
                "R                复位到航线",
                "P / ESC          暂停",
                "滚轮             缩放视角",
                "",
                "按 空格 开始比赛（对手：3 名常规 AI + 63 号最优线）"
        };
        for (int i = 0; i < lines.length; i++) g.drawString(lines[i], 70, 300 + i * 26);

        g.setFont(fBold);
        g.setColor(new Color(0xff7a3d));
        g.drawString(best > 0 ? ("最佳单圈  " + fmt(best)) : "最佳单圈  --:--.-",
                70, 300 + lines.length * 26 + 20);
    }

    /* ==================== 启动 ==================== */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                JFrame f = new JFrame("湖上遥帆 · REGATTA (Java 2D v0.1)");

                // 程序化生成多尺寸帆船图标，任务栏/标题栏各取最合适尺寸
                java.util.List<Image> icons = new ArrayList<>();
                for (int sz : new int[]{16, 24, 32, 48, 64, 128, 256}) icons.add(makeAppIcon(sz));
                f.setIconImages(icons);

                LakeRegatta p = new LakeRegatta();
                f.setContentPane(p);
                f.setSize(1280, 800);
                f.setMinimumSize(new Dimension(900, 600));
                f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
                f.setLocationRelativeTo(null);
                f.setVisible(true);
                p.requestFocusInWindow();
                new javax.swing.Timer(16, p).start();
            } catch (Throwable t) {
                t.printStackTrace();
                JOptionPane.showMessageDialog(null,
                        "启动失败: " + t + "\n详见控制台", "错误", JOptionPane.ERROR_MESSAGE);
            }
        });
    }
}