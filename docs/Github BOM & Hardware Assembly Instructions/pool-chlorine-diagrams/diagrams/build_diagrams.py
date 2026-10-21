#!/usr/bin/env python3
"""Generate the four editable SVG diagrams. Python standard library only.

Run from any directory: python3 diagrams/build_diagrams.py
PNG copies are publication exports; regenerate them with an SVG renderer.
The diagrams describe the documented build and referenced software, not a
dimensioned fabrication drawing or certification of the installation.
"""
from pathlib import Path
from html import escape
import math
import re

OUT = Path(__file__).resolve().parent
INK = '#142C3E'
MUTED = '#526774'
LINE = '#CDD9DF'
BG = '#F7FAFC'
TEAL = '#007F79'
PALE = '#E3F4F1'
BLUE = '#2876AF'
WATER = '#DEEFF9'
PURPLE = '#6A4BB2'
PLUM = '#F0EBFA'
AMBER = '#99620A'
CREAM = '#FFF4DE'
RED = '#A74135'
ROSE = '#FCECE8'

class Figure:
    def __init__(self, number, title, subtitle, height, desc):
        self.h = height
        self.parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="1400" height="{height}" viewBox="0 0 1400 {height}" role="img" aria-labelledby="title desc">', f'<title id="title">{escape(title)}</title>', f'<desc id="desc">{escape(desc)}</desc>']
        self.rect(0,0,1400,height,'#FFFFFF',None,0)
        self.rect(0,0,1400,12,TEAL,None,0)
        self.text(48,54,f'POOL CHLORINE DOSING  /  FIGURE {number}',16,TEAL,True)
        self.text(48,102,title,36,INK,True)
        self.text(48,140,subtitle,20,MUTED)
    def rect(self,x,y,w,h,fill=BG,stroke=LINE,r=14,sw=1.5,dash=None):
        self.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill or "none"}" stroke="{stroke or "none"}" stroke-width="{sw}"'+(f' stroke-dasharray="{dash}"' if dash else '')+'/>')
    def text(self,x,y,s,size=22,color=INK,bold=False,anchor='start'):
        self.parts.append(f'<text x="{x}" y="{y}" font-family="Arial, Helvetica, sans-serif" font-size="{size}" font-weight="{"700" if bold else "400"}" text-anchor="{anchor}" fill="{color}">{escape(s)}</text>')
    def lines(self,x,y,lines,size=21,color=INK,bold=False,anchor='start',leading=None):
        for i,s in enumerate(lines): self.text(x,y+i*(leading or size*1.4),s,size,color,bold,anchor)
    def path(self,d,color=INK,width=3,fill='none',dash=None):
        self.parts.append(f'<path d="{d}" fill="{fill}" stroke="{color}" stroke-width="{width}" stroke-linejoin="round" stroke-linecap="round"'+(f' stroke-dasharray="{dash}"' if dash else '')+'/>')
    def poly(self,pts,fill,stroke=None,sw=2):
        self.parts.append('<polygon points="'+' '.join(f'{x},{y}' for x,y in pts)+f'" fill="{fill}" stroke="{stroke or "none"}" stroke-width="{sw}" stroke-linejoin="round"/>')
    def line(self,pts,color=INK,width=3,dash=None):
        self.path('M '+' L '.join(f'{x} {y}' for x,y in pts),color,width,dash=dash)
    def arrow(self,pts,color=TEAL,width=4,dash=None,head=12):
        self.line(pts,color,width,dash)
        x,y=pts[-1]; xp,yp=pts[-2]; a=math.atan2(y-yp,x-xp)
        self.poly([(x,y),(x-head*math.cos(a)+head*.5*math.sin(a),y-head*math.sin(a)-head*.5*math.cos(a)),(x-head*math.cos(a)-head*.5*math.sin(a),y-head*math.sin(a)+head*.5*math.cos(a))],color)
    def circle(self,x,y,r,fill='#FFFFFF',stroke=INK,sw=2):
        self.parts.append(f'<circle cx="{x}" cy="{y}" r="{r}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>')
    def ellipse(self,x,y,rx,ry,fill,stroke=INK,sw=2):
        self.parts.append(f'<ellipse cx="{x}" cy="{y}" rx="{rx}" ry="{ry}" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>')
    def node(self,x,y,w,h,title,body=(),fill=BG,stroke=LINE,ts=23,bs=19):
        self.rect(x,y,w,h,fill,stroke)
        self.text(x+w/2,y+33,title,ts,INK,True,'middle')
        self.lines(x+w/2,y+64,body,bs,MUTED,anchor='middle',leading=27)
    def diamond(self,cx,cy,w,h,title,body=()):
        self.poly([(cx,cy-h/2),(cx+w/2,cy),(cx,cy+h/2),(cx-w/2,cy)],CREAM,AMBER)
        self.text(cx,cy-7 if body else cy+7,title,23,INK,True,'middle')
        self.lines(cx,cy+24,body,18,MUTED,anchor='middle',leading=23)
    def footer(self,s):
        self.line([(48,self.h-61),(1352,self.h-61)],LINE,1.5)
        self.text(48,self.h-30,s,16,MUTED)
        self.text(1352,self.h-30,'21 OCT 2026',15,MUTED,anchor='end')
    def save(self,name):
        svg = '\n'.join(self.parts+['</svg>'])+'\n'
        # Explicit opacity avoids a stroke-state bug in some SVG rasterizers.
        svg = re.sub(r'stroke="(#[A-Fa-f0-9]+)"',r'stroke="\1" stroke-opacity="1"',svg)
        (OUT/(name+'.svg')).write_text(svg)

def system():
    f=Figure('01','The physical dosing path','Documented arrangement: direct discharge beside a pool return.',1000,
      'Sodium hypochlorite travels from a 15 gallon reservoir through a suction strainer and continuous tube to the Kamoer pump, then through discharge tubing and a check valve into the pool. The valve is submerged near, but not connected to, the wall return. Power and control are shown separately in Figure 03. Internal pickup is schematic; elevations are not specified.')
    f.rect(48,181,1304,556,BG,None)
    f.text(92,225,'1  RESERVOIR',18,TEAL,True)
    f.text(500,225,'2  PERISTALTIC PUMP',18,TEAL,True)
    f.text(974,225,'3  POOL OUTLET',18,TEAL,True)
    # Reservoir: translucent cutaway shows intended pickup, not a fabricated internal photo.
    f.rect(105,348,240,276,'#EAF2F8',BLUE,32,3)
    f.ellipse(225,348,120,32,'#D5E5F0',BLUE,3)
    f.rect(113,446,224,146,'#BAE1DA',None,0)
    f.path('M 113 446 C 160 432 190 460 225 446 C 270 433 300 458 337 446',TEAL,2)
    f.ellipse(225,594,112,24,'#BAE1DA',TEAL,1)
    f.rect(147,314,49,25,'#FFFFFF',BLUE,5)
    f.rect(254,314,49,25,'#FFFFFF',BLUE,5)
    f.line([(279,563),(279,285),(510,285),(510,435),(570,435)],INK,12)
    f.arrow([(279,520),(279,472)],TEAL,4)
    f.arrow([(336,285),(424,285)],TEAL,5)
    f.rect(259,548,40,54,'#435965',INK,5)
    for y in [561,572,583]: f.line([(266,y),(291,y)],'#D7E3E8',2)
    f.text(190,400,'15 US gal',24,INK,True,'middle')
    f.text(190,429,'nominal capacity',17,MUTED,anchor='middle')
    f.text(225,661,'Suction strainer',21,INK,True,'middle')
    f.text(225,688,'Internal pickup is schematic',17,MUTED,anchor='middle')
    f.text(364,326,'Suction',19,TEAL,True)
    # Pump, with tube path through head and directional labels.
    f.rect(571,343,231,234,'#FFFFFF',INK,22,3)
    f.text(686,378,'Kamoer',25,INK,True,'middle')
    f.circle(686,460,64,'#E4ECEF',INK,3)
    f.path('M 576 435 L 626 435 C 626 395 747 395 747 435 L 747 483 L 801 483',TEAL,10)
    for x,y in [(669,434),(714,452),(671,485)]: f.circle(x,y,12,'#FFFFFF',INK,2)
    f.circle(757,541,16,'#D8E2E8',INK)
    f.text(686,613,'KCPA600-N40 • 24 V DC',22,INK,True,'middle')
    f.text(686,644,'Norprene pump-head tube',19,MUTED,anchor='middle')
    f.text(686,672,'Local speed knob; timed power',18,MUTED,anchor='middle')
    f.text(594,326,'IN',17,TEAL,True)
    f.text(809,468,'OUT',17,TEAL,True)
    # Pool edge and outlet. A visible gap separates valve from return fitting.
    f.rect(1005,393,305,275,WATER,None,0)
    f.path('M 1005 393 Q 1030 381 1055 393 T 1105 393 T 1155 393 T 1205 393 T 1255 393 T 1305 393',BLUE,3)
    f.line([(1309,342),(1309,669),(1005,669)],'#9AACB7',9)
    f.line([(802,483),(923,483),(923,315),(1085,315),(1085,495)],INK,12)
    f.arrow([(975,315),(1045,315)],TEAL,5)
    f.arrow([(1085,405),(1085,455)],TEAL,5)
    f.rect(1067,493,36,56,'#FDFDFC',INK,5,2.5)
    f.rect(1077,549,16,21,'#FDFDFC',INK,3)
    f.arrow([(1085,575),(1085,620)],TEAL,5)
    f.circle(1286,530,25,'#FFFFFF',BLUE,3)
    f.circle(1286,530,10,'#A9CEE3',BLUE,2)
    f.arrow([(1253,530),(1179,530)],BLUE,4)
    f.text(1130,586,'Check valve',19,INK,True)
    f.lines(1146,625,['Wall return','separate from valve'],17,MUTED,anchor='middle',leading=23)
    f.text(997,289,'Discharge tube',19,TEAL,True)
    f.text(1157,713,'Submerged outlet; no air gap',19,INK,True,'middle')
    # Concise constraints stay outside the schematic.
    f.rect(48,762,634,155,PALE,None)
    f.text(73,797,'FOLLOW THE ARROWS',17,TEAL,True)
    f.lines(73,829,['Green: chlorine delivery direction. Blue: return water.',
                    'Dark lines: nominal 1/4-inch OD external tubing.',
                    'The check valve is not tapped into the return pipe.'],20,INK,leading=28)
    f.rect(704,762,648,155,CREAM,None)
    f.text(729,797,'LAYOUT LIMITS',17,AMBER,True)
    f.lines(729,829,['Not to scale; tank/pool elevations are not established.',
                    'Validate outlet support, siphon/backflow protection',
                    'and a physical no-flow cutoff before operation.'],20,INK,leading=28)
    f.footer('Basis: assembly photos IMG_0542 to IMG_0551 and README assembly section. Power/control: Figure 03.')
    f.save('01-system-layout')

def reservoir():
    f=Figure('02','Reservoir and pickup cutaway','Observed cap arrangement, with the intended internal pickup shown schematically.',1130,
      'A continuous suction tube passes through a gland in one cap and reaches a suction strainer inside the reservoir. Flow is upward toward the pump. The separate cap provides filling access and shows a small vent hole in the photo. Seal materials, mounting dimensions, vent capacity and pickup position require specifications. A separate cross section distinguishes liquid inside the tube from a gland around its outside.')
    f.rect(48,184,722,704,BG,None)
    f.rect(112,355,530,424,'#EAF2F8',BLUE,38,3)
    f.ellipse(377,355,265,52,'#D9E7F0',BLUE,3)
    f.rect(123,514,508,217,'#BDE2D9',None,0)
    f.ellipse(377,730,254,35,'#BDE2D9',TEAL,1)
    f.path('M 123 514 C 200 497 270 531 350 514 S 510 531 631 514',TEAL,2)
    f.text(310,593,'Liquid sodium hypochlorite',23,INK,True,'middle')
    f.text(310,625,'15 US gal nominal reservoir',21,MUTED,anchor='middle')
    f.text(310,654,'Actual fill level is recorded separately',17,MUTED,anchor='middle')
    # Separate fill/vent cap and tube pass-through cap.
    f.rect(182,311,103,31,'#FFFFFF',BLUE,6,3)
    f.circle(233,318,3,INK,INK,1)
    f.rect(467,309,108,34,'#FFFFFF',BLUE,6,3)
    f.rect(498,270,44,43,'#E3E8EB',INK,5,2)
    f.rect(492,261,56,21,'#F6F7F7',INK,4,2)
    # Liquid remains in the uninterrupted tube, not in the gland body.
    f.line([(520,710),(520,229),(711,229)],INK,18)
    f.line([(520,710),(520,229),(711,229)],'#FFFFFF',9)
    f.arrow([(520,478),(520,420)],TEAL,4)
    f.arrow([(578,229),(684,229)],TEAL,4)
    f.rect(494,696,52,61,'#435965',INK,6,2)
    for y in [710,723,736]: f.line([(503,y),(536,y)],'#D7E3E8',2)
    f.text(556,204,'To pump inlet',20,TEAL,True)
    f.text(88,217,'Separate fill/vent cap',22,INK,True)
    f.lines(88,247,['Small open hole visible in photo.', 'Size and vent capacity unverified.'],18,MUTED,leading=25)
    f.line([(233,280),(233,304)],MUTED,2)
    f.text(112,823,'Pickup strainer, not an assumed foot valve',21,INK,True)
    f.line([(504,754),(504,785),(420,785)],MUTED,2)
    f.text(112,854,'Set pickup position using the component instructions.',18,MUTED)
    # Cap pass-through detail.
    f.rect(794,184,558,445,'#FFFFFF',LINE)
    f.text(820,222,'CONTINUOUS-TUBE PASS-THROUGH',18,TEAL,True)
    f.text(820,252,'Conceptual section; not a machining drawing',18,MUTED)
    f.rect(835,366,474,39,'#CCDCE7',BLUE,1,2)
    f.text(835,445,'Reservoir cap',18,MUTED)
    f.line([(906,423),(906,407)],MUTED,2)
    f.rect(1002,306,104,161,'#D8E0E5',INK,4,2)
    f.poly([(980,319),(1002,295),(1106,295),(1128,319),(1106,343),(1002,343)],'#F2F5F6',INK,2)
    f.rect(1014,334,80,35,'#AEC0C8',INK,2,1)
    f.rect(1038,280,32,231,INK,None,0)
    f.rect(1047,280,14,231,'#FFFFFF',None,0)
    f.arrow([(1054,480),(1054,421)],TEAL,4)
    f.text(1172,307,'Tube',19,INK,True)
    f.line([(1162,300),(1075,300)],MUTED,2)
    f.lines(1153,337,['Gland grips','tube outside'],18,INK,leading=23)
    f.line([(1140,349),(1095,349)],MUTED,2)
    f.lines(820,555,['Liquid stays inside the unbroken tube.',
                     'The gland seals/grips around the tube exterior.',
                     'A hose barb is a different connection type.'],20,INK,leading=28)
    f.rect(794,651,558,237,PALE,None)
    f.text(820,690,'WHAT TO SPECIFY',18,TEAL,True)
    f.lines(820,727,['• Tube OD and gland grip range',
                     '• Body/seal compatibility with the chemical',
                     '• Cap hole, gasket and mounting details',
                     '• Approved venting for tank and chemical',
                     '• Secure pickup with no kinks or abrasion'],20,INK,leading=29)
    f.rect(48,911,1304,129,CREAM,None)
    f.text(73,948,'READ WITH THE ASSEMBLY PHOTOS',17,AMBER,True)
    f.lines(73,980,['Cap positions and internal geometry are illustrative. No drilling diameter, thread assignment or airtight seal is specified.',
                    'Provide dedicated containment. A cable-gland ingress rating does not establish chemical-service approval.'],20,INK,leading=29)
    f.footer('Basis: cap photos IMG_0545 / IMG_0547, original pickup-parts photo and README connection table.')
    f.save('02-reservoir-cutaway')

def control():
    f=Figure('03','Power, data and control','Functional connections only. The pump is powered through the Z-Wave plug.',1100,
      'WaterGuru measurements reach the vendor cloud and the Hubitat integration and advisor. Hubitat sends ON and OFF commands to the Zooz ZEN05 and receives switch and power reports. Required protected AC runs through the plug to the matched 24 volt adapter and pump. Planned runtime and emergency cutoff are Hubitat software controls; the plug local timer is separate but all depend on the same outlet relay.')
    f.rect(48,180,1304,279,PLUM,None)
    f.text(71,217,'DATA AND COMMANDS',17,PURPLE,True)
    f.node(73,242,267,136,'WaterGuru',['Periodic chemistry','measurements'],'#FFFFFF',None)
    f.node(415,242,267,136,'Vendor cloud',['Network / cloud','connection required'],'#FFFFFF',None)
    f.node(759,242,568,136,'Hubitat',['WaterGuru Integration + Dosing Advisor','Calculations, limits, modes and notifications'],'#FFFFFF',None)
    f.arrow([(340,309),(415,309)],PURPLE,4,'9 7')
    f.arrow([(682,309),(759,309)],PURPLE,4,'9 7')
    # Different connector routes for outbound control and inbound reports.
    f.arrow([(860,378),(860,427),(555,427),(555,555)],PURPLE,3.5,'9 7')
    f.text(581,414,'Z-Wave ON / OFF',18,PURPLE,True)
    f.arrow([(619,555),(619,485),(1179,485),(1179,378)],PURPLE,3.5,'9 7')
    f.text(865,513,'Switch + measured-power reports',18,PURPLE,True)
    f.text(71,539,'POWER PATH',17,AMBER,True)
    f.node(73,555,267,154,'AC supply',['Required GFCI protection','and outdoor provisions'],CREAM,None)
    f.node(415,555,267,154,'Zooz ZEN05',['800LR Z-Wave outlet','One switched relay'],CREAM,None)
    f.node(759,555,267,154,'Matched adapter',['Switched AC input','24 V DC output'],CREAM,None)
    f.node(1101,555,226,154,'KCPA600',['24 V DC pump','Local speed knob'],CREAM,None)
    for a,b in [(340,415),(682,759),(1026,1101)]: f.arrow([(a,628),(b,628)],AMBER,5)
    f.text(377,609,'AC',17,AMBER,True,'middle')
    f.text(719,609,'AC',17,AMBER,True,'middle')
    f.text(1063,609,'DC',17,AMBER,True,'middle')
    f.text(1214,744,'No pump Wi-Fi setup',18,INK,True,'middle')
    f.text(71,788,'REFERENCE RUNTIME LIMITS',17,INK,True)
    f.node(73,808,394,108,'14 min maximum',['Planned dose limit in Hubitat'],BG,LINE,24,19)
    f.node(489,808,394,108,'15 min app cutoff',['Additional Hubitat OFF timer'],BG,LINE,24,19)
    f.node(905,808,422,108,'20 min plug auto-off',['Local timer; configure on ZEN05'],BG,LINE,24,19)
    f.lines(73,957,['Hubitat timers share the hub. The local timer runs on the plug. All depend on the same outlet relay opening.',
                   'Power reports do not prove liquid flow. A separate physical circulation cutoff is not documented in the photos.'],20,INK,leading=29)
    f.footer('Basis: README reference settings, Dosing Advisor 2.5.0 (3dd9a71) and Zooz ZEN05 advanced settings.')
    f.save('03-power-and-control')

def sequence():
    f=Figure('04','From measurement to confirmed shutdown','Normal dosing and fault recovery in WaterGuru Dosing Advisor 2.5.0.',1700,
      'Advisory mode calculates only. Approval waits for the operator; Auto can authorize an eligible sample. Guards must pass before allowance is reserved, timers are armed and ON is requested. Fresh ON and adequate power confirm a start on this power-reporting plug. Failure causes OFF and a latched fault. A monitored run ends with an OFF request; without fresh OFF confirmation, retries and cutoff protection remain active. Usage is estimated, not metered.')
    f.node(48,177,410,110,'ADVISORY',['Calculate and notify; no pump start'],BG,LINE,22,18)
    f.node(480,177,410,110,'APPROVAL',['Operator authorizes the pending dose'],PLUM,None,22,18)
    f.node(912,177,440,110,'AUTO',['Eligible sample may authorize a dose'],PLUM,None,22,18)
    cx=440
    # Main path drawn from top to bottom. Explanatory cards occupy a separate column.
    f.node(90,324,700,82,'New sample + run authorization',[],BG,LINE,25)
    f.arrow([(cx,406),(cx,438)])
    f.diamond(cx,502,700,128,'All dosing guards pass?',['Mode, sample, circulation, chemistry and limits'])
    f.arrow([(790,502),(930,502)],RED,4)
    f.text(842,486,'NO',18,RED,True)
    f.node(930,454,422,105,'Hold and report why',['No ON request'],ROSE,None)
    f.arrow([(cx,566),(cx,600)])
    f.text(459,591,'YES',17,TEAL,True)
    f.node(90,600,700,106,'Reserve attempt; arm stop protection',['Request ON once; sample and dose allowance are reserved'],PALE,None,24,18)
    f.arrow([(cx,706),(cx,740)])
    f.diamond(cx,807,700,134,'Start confirmed in time?',['Fresh ON + fresh adequate watts for this plug'])
    f.arrow([(790,807),(930,807)],RED,4)
    f.text(842,790,'NO',18,RED,True)
    f.node(930,756,422,110,'Latch fault; request OFF',['Keep reservation; no automatic retry'],ROSE,None,22,17)
    f.arrow([(cx,874),(cx,911)])
    f.text(459,899,'YES',17,TEAL,True)
    f.node(90,911,700,106,'Monitor the bounded run',['Watch power evidence and runtime; usage remains estimated'],PALE,None,24,18)
    f.arrow([(cx,1017),(cx,1052)])
    f.node(90,1052,700,106,'Request OFF',['Planned end, stop command or fault'],BG,LINE,24,20)
    f.arrow([(cx,1158),(cx,1194)])
    f.diamond(cx,1260,700,132,'Fresh OFF confirmed?',['An old cached OFF is insufficient for this run'])
    f.arrow([(790,1260),(930,1260)],RED,4)
    f.text(842,1243,'NO',18,RED,True)
    f.node(930,1198,422,146,'Continue stop recovery',['Retry OFF; keep cutoff protection','Escalate / notify as appropriate','Block another dose while recovering'],ROSE,None,22,17)
    # Failed start feeds the common stop-and-confirm path, not the successful run.
    f.arrow([(1141,866),(1141,1083),(790,1083)],RED,3.5)
    f.text(952,949,'Shared stop path',18,RED,True)
    # Retry returns to the OFF request via the outer margin, clear of the normal path.
    f.arrow([(1352,1270),(1372,1270),(1372,1127),(790,1127)],RED,3.5)
    f.arrow([(cx,1326),(cx,1420)])
    f.text(459,1370,'YES',17,TEAL,True)
    f.node(90,1420,700,108,'Close run; reconcile estimated usage',['Notify outcome; retained faults still require review'],PALE,None,24,18)
    f.rect(930,600,422,105,BG,None)
    f.text(953,633,'RESERVATION IS DELIBERATE',16,MUTED,True)
    f.lines(953,663,['A failed start does not free its sample', 'or reserved daily dose allowance.'],18,MUTED,leading=24)
    f.lines(48,1583,['A fresh power report confirms electrical load, not chemical flow. Fault acknowledgement never starts the pump.',
                    'This overview is for a dosing attempt; see the software documentation for idle-stop and recovery edge cases.'],19,INK,leading=27)
    f.footer('Basis: published Dosing Advisor README at commit 3dd9a71. Limits and device setup: Figure 03 / tutorial.')
    f.save('04-dosing-sequence')

if __name__ == '__main__':
    OUT.mkdir(parents=True,exist_ok=True)
    system(); reservoir(); control(); sequence()
    print('Generated four SVG diagrams in', OUT)
