'use client';

import React, { useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/context/AuthContext';

export default function Home() {
  const router = useRouter();
  const { token, isLoading } = useAuth();
  const [activeTab, setActiveTab] = useState<'all' | 'active' | 'recovered'>('all');
  const [activeStep, setActiveStep] = useState(2);

  useEffect(() => {
    if (!isLoading && token) {
      router.push('/dashboard');
    }
  }, [token, isLoading, router]);

  const pipelineSteps = [
    { num: '01', title: 'Payment Failed', code: 'GATEWAY_DECLINED', desc: 'Webhook event received from Razorpay', tag: 'Ingestion' },
    { num: '02', title: 'Webhook Received', code: 'REDIS_STREAM', desc: 'Ingested into mend:webhooks stream with strict idempotency', tag: 'Pipeline' },
    { num: '03', title: 'Event Classified', code: 'AI_INFERENCE', desc: 'Failure diagnosed as INSUFFICIENT_FUNDS (96% confidence)', tag: 'Diagnosis' },
    { num: '04', title: 'Campaign Created', code: 'STATE_INITIATED', desc: 'Recovery campaign bound to tenant and subscription ID', tag: 'State Machine' },
    { num: '05', title: 'Recovery Strategy', code: 'SMART_RETRY', desc: 'Optimal retry window selected based on past success patterns', tag: 'Strategy' },
    { num: '06', title: 'Compliance Check', code: 'POLICY_APPROVED', desc: 'Validated maxAttempts (1/3) and 24h contact window', tag: 'Safety Gate' },
    { num: '07', title: 'Action Intent', code: 'IDEMPOTENT_LOCK', desc: 'Scheduled execution intent with locked worker claim', tag: 'Orchestration' },
    { num: '08', title: 'Payment Retry', code: 'RAZORPAY_API', desc: 'Executed automated retry attempt via Razorpay client', tag: 'Provider' },
    { num: '09', title: 'Outcome Resolved', code: 'RECOVERED', desc: 'Payment settled. ₹4,999 revenue successfully recovered', tag: 'Settlement' },
  ];

  return (
    <div className="min-h-screen bg-[#090d16] text-slate-100 font-sans selection:bg-blue-600 selection:text-white">
      {/* Navigation Topbar */}
      <header className="sticky top-0 z-50 bg-[#090d16]/90 backdrop-blur-md border-b border-slate-800/80 px-6 py-4">
        <div className="max-w-7xl mx-auto flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <div className="w-9 h-9 rounded-xl bg-gradient-to-tr from-blue-600 to-indigo-600 flex items-center justify-center text-white font-black text-xl shadow-lg shadow-blue-500/20">
              M
            </div>
            <div>
              <span className="font-extrabold text-xl tracking-tight text-white">Mend</span>
              <span className="ml-2 text-[11px] px-2 py-0.5 rounded font-mono font-bold bg-blue-950 text-blue-400 border border-blue-800/60 uppercase">
                Console
              </span>
            </div>
          </div>

          <nav className="hidden md:flex items-center space-x-8 text-sm font-semibold text-slate-400">
            <a href="#pipeline" className="hover:text-white transition-colors">Operational Flow</a>
            <a href="#dashboard" className="hover:text-white transition-colors">Dashboard</a>
            <a href="#campaigns" className="hover:text-white transition-colors">Campaigns</a>
            <a href="#compliance" className="hover:text-white transition-colors">Compliance</a>
            <a href="#audit" className="hover:text-white transition-colors">Audit Trail</a>
          </nav>

          <div className="flex items-center space-x-4">
            <Link
              href="/login"
              className="px-5 py-2.5 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-bold text-sm shadow-md shadow-blue-600/30 transition-all flex items-center space-x-2"
            >
              <span>Open Merchant Console</span>
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M14 5l7 7m0 0l-7 7m7-7H3" />
              </svg>
            </Link>
          </div>
        </div>
      </header>

      {/* HERO SECTION */}
      <section className="relative pt-16 pb-24 px-6 max-w-7xl mx-auto overflow-hidden">
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-12 items-center">
          
          {/* Left Column - Headline & CTAs */}
          <div className="lg:col-span-6 space-y-8 z-10">
            <div className="inline-flex items-center space-x-2 px-3 py-1 rounded-full bg-blue-950/80 border border-blue-800/80 text-blue-400 text-xs font-mono font-semibold">
              <span className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
              <span>Razorpay Payment Recovery Engine &bull; Enterprise Operations</span>
            </div>

            <h1 className="text-4xl sm:text-5xl lg:text-6xl font-black tracking-tight text-white leading-[1.1]">
              Mend Payment Recovery Operations
            </h1>

            <p className="text-base sm:text-lg text-slate-300 font-normal leading-relaxed max-w-xl">
              Failed payments are detected, classified, evaluated against recovery policy, and routed through safe, compliant recovery actions.
            </p>

            <div className="flex flex-col sm:flex-row items-stretch sm:items-center gap-4 pt-2">
              <Link
                href="/login"
                className="px-7 py-3.5 rounded-xl bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-500 hover:to-indigo-500 text-white font-bold text-base shadow-xl shadow-blue-600/25 transition-all text-center"
              >
                Launch Console
              </Link>
              <a
                href="#pipeline"
                className="px-7 py-3.5 rounded-xl bg-slate-800/90 hover:bg-slate-800 text-slate-200 font-semibold text-base border border-slate-700 transition-colors text-center"
              >
                View Operational Flow &darr;
              </a>
            </div>

            {/* Quick Metrics Strip */}
            <div className="grid grid-cols-3 gap-4 pt-6 border-t border-slate-800/80 text-left">
              <div>
                <div className="text-2xl font-extrabold text-white font-mono">63.8%</div>
                <div className="text-xs text-slate-400 font-medium">Recovery Rate</div>
              </div>
              <div>
                <div className="text-2xl font-extrabold text-emerald-400 font-mono">₹18.42L</div>
                <div className="text-xs text-slate-400 font-medium">Revenue Managed</div>
              </div>
              <div>
                <div className="text-2xl font-extrabold text-blue-400 font-mono">248</div>
                <div className="text-xs text-slate-400 font-medium">Auto-Recovered</div>
              </div>
            </div>
          </div>

          {/* Right Column - Realistic 3D Depth Console Preview */}
          <div className="lg:col-span-6 perspective-1000">
            <div className="card-3d-tilt card-3d-float rounded-2xl bg-[#0f172a] border border-slate-700/80 p-6 space-y-6 relative overflow-hidden">
              {/* Header controls bar */}
              <div className="flex items-center justify-between border-b border-slate-800 pb-4">
                <div className="flex items-center space-x-2">
                  <span className="w-3 h-3 rounded-full bg-rose-500" />
                  <span className="w-3 h-3 rounded-full bg-amber-500" />
                  <span className="w-3 h-3 rounded-full bg-emerald-500" />
                  <span className="ml-2 text-xs font-mono text-slate-400 font-semibold">mend-console.merchant.internal</span>
                </div>
                <span className="text-[10px] px-2 py-0.5 rounded bg-emerald-950 text-emerald-400 font-mono border border-emerald-800">
                  LIVE ENGINE ONLINE
                </span>
              </div>

              {/* Console Dashboard Cards */}
              <div className="grid grid-cols-2 gap-3">
                <div className="p-4 rounded-xl bg-slate-900/90 border border-slate-800">
                  <div className="text-xs text-slate-400 font-medium">Revenue at Risk</div>
                  <div className="text-xl font-bold text-white font-mono mt-1">₹18,42,000</div>
                  <div className="text-[10px] text-amber-400 mt-1">388 Failed Subscriptions</div>
                </div>

                <div className="p-4 rounded-xl bg-slate-900/90 border border-slate-800">
                  <div className="text-xs text-slate-400 font-medium">Revenue Recovered</div>
                  <div className="text-xl font-bold text-emerald-400 font-mono mt-1">₹11,76,200</div>
                  <div className="text-[10px] text-emerald-400/80 mt-1">63.8% Success Rate</div>
                </div>
              </div>

              {/* Active Pipeline Preview Card */}
              <div className="p-4 rounded-xl bg-slate-900/80 border border-slate-800/80 space-y-3">
                <div className="flex items-center justify-between text-xs">
                  <span className="font-bold text-slate-300 uppercase tracking-wider">Active Recovery Stream</span>
                  <span className="font-mono text-slate-400">Merchant: MEND_PROD_01</span>
                </div>

                <div className="space-y-2 font-mono text-xs">
                  <div className="flex items-center justify-between p-2.5 rounded-lg bg-slate-950 border border-slate-800">
                    <div>
                      <div className="font-bold text-white">pay_N9k2x1Lp89</div>
                      <div className="text-[10px] text-slate-400">Reason: INSUFFICIENT_FUNDS</div>
                    </div>
                    <div className="text-right">
                      <div className="font-bold text-emerald-400">₹4,999</div>
                      <span className="text-[10px] text-emerald-400 font-semibold uppercase">RECOVERED</span>
                    </div>
                  </div>

                  <div className="flex items-center justify-between p-2.5 rounded-lg bg-slate-950 border border-slate-800">
                    <div>
                      <div className="font-bold text-white">pay_N9k3m4Kp90</div>
                      <div className="text-[10px] text-slate-400">Strategy: SMART_RETRY</div>
                    </div>
                    <div className="text-right">
                      <div className="font-bold text-blue-400">₹12,499</div>
                      <span className="text-[10px] text-blue-400 font-semibold uppercase">ACTION_PENDING</span>
                    </div>
                  </div>
                </div>
              </div>

              {/* Footer status */}
              <div className="flex items-center justify-between text-[11px] text-slate-400 font-mono pt-2 border-t border-slate-800">
                <span>Scheduler: ACTIVE (30s ticker)</span>
                <span>Tenant Isolation: ENFORCED</span>
              </div>
            </div>
          </div>

        </div>
      </section>

      {/* SECTION 1: PRODUCT PIPELINE */}
      <section id="pipeline" className="py-20 px-6 max-w-7xl mx-auto border-t border-slate-800/80">
        <div className="text-center max-w-3xl mx-auto space-y-4 mb-16">
          <div className="inline-block px-3 py-1 rounded-full bg-blue-950 text-blue-400 text-xs font-mono font-bold uppercase border border-blue-800">
            End-to-End Orchestration
          </div>
          <h2 className="text-3xl sm:text-4xl font-extrabold text-white tracking-tight">
            Automated Recovery Execution Flow
          </h2>
          <p className="text-slate-400 text-base">
            Every payment failure moves through a deterministic, policy-bounded 9-stage pipeline from Razorpay webhook ingestion to final settlement reconciliation.
          </p>
        </div>

        {/* Operational Flow Cards */}
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {pipelineSteps.map((step, idx) => (
            <div
              key={step.num}
              onClick={() => setActiveStep(idx)}
              className={`p-5 rounded-2xl border transition-all cursor-pointer ${
                activeStep === idx
                  ? 'bg-slate-800/90 border-blue-500 shadow-lg shadow-blue-500/10 transform -translate-y-1'
                  : 'bg-slate-900/60 border-slate-800 hover:border-slate-700 hover:bg-slate-900'
              }`}
            >
              <div className="flex items-center justify-between mb-3">
                <span className="font-mono text-sm font-bold text-blue-400">{step.num}</span>
                <span className="text-[10px] px-2 py-0.5 rounded font-mono bg-slate-800 text-slate-300 border border-slate-700">
                  {step.tag}
                </span>
              </div>
              <h3 className="text-lg font-bold text-white">{step.title}</h3>
              <div className="font-mono text-xs text-blue-400 font-semibold my-1">{step.code}</div>
              <p className="text-xs text-slate-400 leading-relaxed mt-2">{step.desc}</p>
            </div>
          ))}
        </div>
      </section>

      {/* SECTION 2: REAL PAYMENT RECOVERY DASHBOARD */}
      <section id="dashboard" className="py-20 px-6 max-w-7xl mx-auto border-t border-slate-800/80">
        <div className="flex flex-col md:flex-row md:items-end justify-between mb-12 gap-4">
          <div>
            <span className="text-xs font-mono text-blue-400 font-bold uppercase tracking-wider">Merchant Intelligence</span>
            <h2 className="text-3xl font-extrabold text-white mt-1">Real-Time Operational Analytics</h2>
          </div>
          <div className="flex items-center space-x-2 text-xs font-mono text-slate-400 bg-slate-900 px-4 py-2 rounded-xl border border-slate-800">
            <span className="w-2 h-2 rounded-full bg-emerald-500" />
            <span>Currency: INR (₹) &bull; Context: Razorpay Live</span>
          </div>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
          <div className="p-6 rounded-2xl bg-slate-900 border border-slate-800 space-y-2">
            <div className="text-xs text-slate-400 font-semibold uppercase">Revenue at Risk</div>
            <div className="text-3xl font-black text-white font-mono">₹18.42L</div>
            <div className="text-xs text-slate-400">Total failed payment volume</div>
          </div>

          <div className="p-6 rounded-2xl bg-slate-900 border border-slate-800 space-y-2">
            <div className="text-xs text-slate-400 font-semibold uppercase">Revenue Recovered</div>
            <div className="text-3xl font-black text-emerald-400 font-mono">₹11.76L</div>
            <div className="text-xs text-emerald-400/80">Successfully collected</div>
          </div>

          <div className="p-6 rounded-2xl bg-slate-900 border border-slate-800 space-y-2">
            <div className="text-xs text-slate-400 font-semibold uppercase">Recovery Rate</div>
            <div className="text-3xl font-black text-blue-400 font-mono">63.8%</div>
            <div className="text-xs text-slate-400">248 of 388 campaigns</div>
          </div>

          <div className="p-6 rounded-2xl bg-slate-900 border border-slate-800 space-y-2">
            <div className="text-xs text-slate-400 font-semibold uppercase">Active Campaigns</div>
            <div className="text-3xl font-black text-amber-400 font-mono">247</div>
            <div className="text-xs text-slate-400">Currently in retry cycle</div>
          </div>
        </div>

        {/* Funnel breakdown */}
        <div className="mt-8 p-6 rounded-2xl bg-slate-900/90 border border-slate-800 space-y-6">
          <h3 className="text-base font-bold text-white">Recovery Funnel Breakdown</h3>
          
          <div className="space-y-4">
            <div>
              <div className="flex justify-between text-xs font-semibold mb-1.5">
                <span className="text-slate-300">1. Ingested Failures (388)</span>
                <span className="font-mono text-slate-400">100%</span>
              </div>
              <div className="w-full h-2.5 rounded-full bg-slate-800 overflow-hidden">
                <div className="h-full bg-slate-500 rounded-full w-full" />
              </div>
            </div>

            <div>
              <div className="flex justify-between text-xs font-semibold mb-1.5">
                <span className="text-slate-300">2. Classified & Diagnosed (372)</span>
                <span className="font-mono text-slate-400">95.8%</span>
              </div>
              <div className="w-full h-2.5 rounded-full bg-slate-800 overflow-hidden">
                <div className="h-full bg-blue-600 rounded-full w-[95.8%]" />
              </div>
            </div>

            <div>
              <div className="flex justify-between text-xs font-semibold mb-1.5">
                <span className="text-slate-300">3. Safety Gate Approved (340)</span>
                <span className="font-mono text-slate-400">87.6%</span>
              </div>
              <div className="w-full h-2.5 rounded-full bg-slate-800 overflow-hidden">
                <div className="h-full bg-indigo-600 rounded-full w-[87.6%]" />
              </div>
            </div>

            <div>
              <div className="flex justify-between text-xs font-semibold mb-1.5">
                <span className="text-emerald-400 font-bold">4. Recovered Revenue (248)</span>
                <span className="font-mono text-emerald-400 font-bold">63.8%</span>
              </div>
              <div className="w-full h-2.5 rounded-full bg-slate-800 overflow-hidden">
                <div className="h-full bg-emerald-500 rounded-full w-[63.8%]" />
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* SECTION 3: RECOVERY CAMPAIGNS */}
      <section id="campaigns" className="py-20 px-6 max-w-7xl mx-auto border-t border-slate-800/80">
        <div className="flex flex-col md:flex-row md:items-end justify-between mb-8 gap-4">
          <div>
            <span className="text-xs font-mono text-blue-400 font-bold uppercase tracking-wider">Active Recovery Operations</span>
            <h2 className="text-3xl font-extrabold text-white mt-1">Live Campaign Console</h2>
          </div>
          <div className="flex items-center space-x-2 bg-slate-900 p-1 rounded-xl border border-slate-800 text-xs">
            <button
              onClick={() => setActiveTab('all')}
              className={`px-3 py-1.5 rounded-lg font-semibold transition-colors ${activeTab === 'all' ? 'bg-blue-600 text-white' : 'text-slate-400 hover:text-white'}`}
            >
              All (5)
            </button>
            <button
              onClick={() => setActiveTab('active')}
              className={`px-3 py-1.5 rounded-lg font-semibold transition-colors ${activeTab === 'active' ? 'bg-blue-600 text-white' : 'text-slate-400 hover:text-white'}`}
            >
              Active (3)
            </button>
            <button
              onClick={() => setActiveTab('recovered')}
              className={`px-3 py-1.5 rounded-lg font-semibold transition-colors ${activeTab === 'recovered' ? 'bg-blue-600 text-white' : 'text-slate-400 hover:text-white'}`}
            >
              Recovered (2)
            </button>
          </div>
        </div>

        <div className="bg-slate-900 rounded-2xl border border-slate-800 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="bg-slate-950 border-b border-slate-800 text-xs font-mono uppercase text-slate-400">
                <tr>
                  <th className="px-6 py-4">Campaign</th>
                  <th className="px-6 py-4">Failure Reason</th>
                  <th className="px-6 py-4">Attempt</th>
                  <th className="px-6 py-4">Strategy</th>
                  <th className="px-6 py-4">Status</th>
                  <th className="px-6 py-4 text-right">Amount</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800 font-mono text-xs">
                <tr className="hover:bg-slate-800/40">
                  <td className="px-6 py-4 font-bold text-white">Subscription Renewal</td>
                  <td className="px-6 py-4 text-amber-400">Card Expired</td>
                  <td className="px-6 py-4 text-slate-300">Attempt 2 / 3</td>
                  <td className="px-6 py-4 text-slate-300">Exponential Backoff</td>
                  <td className="px-6 py-4">
                    <span className="px-2 py-0.5 rounded bg-blue-950 text-blue-400 border border-blue-800 font-semibold">SCHEDULED</span>
                  </td>
                  <td className="px-6 py-4 text-right font-bold text-white">₹4,999</td>
                </tr>

                <tr className="hover:bg-slate-800/40">
                  <td className="px-6 py-4 font-bold text-white">Invoice Payment #8402</td>
                  <td className="px-6 py-4 text-amber-400">Insufficient Funds</td>
                  <td className="px-6 py-4 text-slate-300">Attempt 1 / 3</td>
                  <td className="px-6 py-4 text-slate-300">Smart Retry (AI)</td>
                  <td className="px-6 py-4">
                    <span className="px-2 py-0.5 rounded bg-emerald-950 text-emerald-400 border border-emerald-800 font-semibold">ELIGIBLE</span>
                  </td>
                  <td className="px-6 py-4 text-right font-bold text-white">₹12,499</td>
                </tr>

                <tr className="hover:bg-slate-800/40">
                  <td className="px-6 py-4 font-bold text-white">Recurring Membership</td>
                  <td className="px-6 py-4 text-rose-400">Network Failure</td>
                  <td className="px-6 py-4 text-slate-300">Attempt 3 / 3</td>
                  <td className="px-6 py-4 text-slate-300">Escalation</td>
                  <td className="px-6 py-4">
                    <span className="px-2 py-0.5 rounded bg-amber-950 text-amber-400 border border-amber-800 font-semibold">MANUAL_REVIEW</span>
                  </td>
                  <td className="px-6 py-4 text-right font-bold text-white">₹2,499</td>
                </tr>

                <tr className="hover:bg-slate-800/40">
                  <td className="px-6 py-4 font-bold text-white">Pro SaaS Tier B</td>
                  <td className="px-6 py-4 text-emerald-400">Auth Declined</td>
                  <td className="px-6 py-4 text-slate-300">Attempt 1 / 3</td>
                  <td className="px-6 py-4 text-slate-300">Smart Retry</td>
                  <td className="px-6 py-4">
                    <span className="px-2 py-0.5 rounded bg-emerald-950 text-emerald-400 border border-emerald-800 font-semibold">RECOVERED</span>
                  </td>
                  <td className="px-6 py-4 text-right font-bold text-emerald-400">₹8,999</td>
                </tr>

                <tr className="hover:bg-slate-800/40">
                  <td className="px-6 py-4 font-bold text-white">Enterprise Annual Plan</td>
                  <td className="px-6 py-4 text-rose-400">Account Blocked</td>
                  <td className="px-6 py-4 text-slate-300">Attempt 4 / 3</td>
                  <td className="px-6 py-4 text-slate-300">Policy Block</td>
                  <td className="px-6 py-4">
                    <span className="px-2 py-0.5 rounded bg-rose-950 text-rose-400 border border-rose-800 font-semibold">TERMINATED</span>
                  </td>
                  <td className="px-6 py-4 text-right font-bold text-slate-500">₹45,000</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>

      {/* SECTION 4: RECOVERY DECISION ENGINE */}
      <section id="engine" className="py-20 px-6 max-w-7xl mx-auto border-t border-slate-800/80">
        <div className="text-center max-w-3xl mx-auto space-y-4 mb-16">
          <span className="text-xs font-mono text-blue-400 font-bold uppercase">Decision Intelligence</span>
          <h2 className="text-3xl sm:text-4xl font-extrabold text-white">Bounded Recovery Strategy Engine</h2>
          <p className="text-slate-400 text-base">
            Mend uses failure classification and historical settlement data to select optimal retry strategies without blind or unauthorized payment retries.
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-4 gap-6">
          <div className="p-6 rounded-2xl bg-slate-900 border border-slate-800 space-y-3">
            <div className="w-8 h-8 rounded-lg bg-amber-950 text-amber-400 flex items-center justify-center font-bold text-sm">
              01
            </div>
            <h3 className="font-bold text-white text-base">Card Expired</h3>
            <div className="text-xs font-mono text-amber-400 font-semibold">ACTION: CUSTOMER_NOTIFY</div>
            <p className="text-xs text-slate-400 leading-relaxed">
              Triggers card update portal request instead of retrying an invalid card.
            </p>
          </div>

          <div className="p-6 rounded-2xl bg-slate-900 border border-slate-800 space-y-3">
            <div className="w-8 h-8 rounded-lg bg-blue-950 text-blue-400 flex items-center justify-center font-bold text-sm">
              02
            </div>
            <h3 className="font-bold text-white text-base">Gateway Timeout</h3>
            <div className="text-xs font-mono text-blue-400 font-semibold">ACTION: RETRY_15M</div>
            <p className="text-xs text-slate-400 leading-relaxed">
              Executes immediate short-window retry when bank infrastructure resolves.
            </p>
          </div>

          <div className="p-6 rounded-2xl bg-slate-900 border border-slate-800 space-y-3">
            <div className="w-8 h-8 rounded-lg bg-indigo-950 text-indigo-400 flex items-center justify-center font-bold text-sm">
              03
            </div>
            <h3 className="font-bold text-white text-base">Insufficient Funds</h3>
            <div className="text-xs font-mono text-indigo-400 font-semibold">ACTION: SMART_BACKOFF</div>
            <p className="text-xs text-slate-400 leading-relaxed">
              Schedules retries around typical payroll processing days (1st, 15th, 30th).
            </p>
          </div>

          <div className="p-6 rounded-2xl bg-slate-900 border border-slate-800 space-y-3">
            <div className="w-8 h-8 rounded-lg bg-rose-950 text-rose-400 flex items-center justify-center font-bold text-sm">
              04
            </div>
            <h3 className="font-bold text-white text-base">Repeated Failures</h3>
            <div className="text-xs font-mono text-rose-400 font-semibold">ACTION: ESCALATE</div>
            <p className="text-xs text-slate-400 leading-relaxed">
              Transfers campaign to merchant team review after exceeding retry limits.
            </p>
          </div>
        </div>
      </section>

      {/* SECTION 5: SAFETY / COMPLIANCE */}
      <section id="compliance" className="py-20 px-6 max-w-7xl mx-auto border-t border-slate-800/80">
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-12 items-center">
          <div className="lg:col-span-6 space-y-6">
            <div className="inline-block px-3 py-1 rounded-full bg-emerald-950 text-emerald-400 text-xs font-mono font-bold uppercase border border-emerald-800">
              Safety Boundary
            </div>
            <h2 className="text-3xl font-extrabold text-white tracking-tight">
              Deterministic Compliance Gate
            </h2>
            <p className="text-slate-300 text-base leading-relaxed">
              No recovery action can execute without passing strict tenant policy verification, contact frequency rules, and idempotent worker locks.
            </p>

            <div className="space-y-3 font-mono text-xs">
              <div className="flex items-center space-x-3 p-3 rounded-xl bg-slate-900 border border-slate-800">
                <span className="w-5 h-5 rounded-full bg-emerald-950 text-emerald-400 flex items-center justify-center font-bold">✓</span>
                <span className="text-slate-200">Tenant context verified (<code className="text-blue-400">X-Merchant-Id</code> isolated)</span>
              </div>
              <div className="flex items-center space-x-3 p-3 rounded-xl bg-slate-900 border border-slate-800">
                <span className="w-5 h-5 rounded-full bg-emerald-950 text-emerald-400 flex items-center justify-center font-bold">✓</span>
                <span className="text-slate-200">Recovery policy allowed (<code className="text-blue-400">maxAttempts &lt;= 3</code>)</span>
              </div>
              <div className="flex items-center space-x-3 p-3 rounded-xl bg-slate-900 border border-slate-800">
                <span className="w-5 h-5 rounded-full bg-emerald-950 text-emerald-400 flex items-center justify-center font-bold">✓</span>
                <span className="text-slate-200">Contact window valid (<code className="text-blue-400">contactWindowHours &lt;= 24</code>)</span>
              </div>
              <div className="flex items-center space-x-3 p-3 rounded-xl bg-slate-900 border border-slate-800">
                <span className="w-5 h-5 rounded-full bg-emerald-950 text-emerald-400 flex items-center justify-center font-bold">✓</span>
                <span className="text-slate-200">Idempotency lock active (<code className="text-blue-400">idempotencyKey verified</code>)</span>
              </div>
            </div>
          </div>

          <div className="lg:col-span-6">
            <div className="p-8 rounded-2xl bg-gradient-to-b from-slate-900 to-slate-950 border border-emerald-800/80 shadow-xl space-y-6">
              <div className="flex items-center justify-between border-b border-slate-800 pb-4">
                <span className="font-mono text-xs text-slate-400 font-bold">COMPLIANCE DECISION RESULT</span>
                <span className="px-3 py-1 rounded bg-emerald-950 text-emerald-400 text-xs font-mono font-bold border border-emerald-800">
                  ACTION APPROVED
                </span>
              </div>

              <div className="space-y-2 font-mono text-xs text-slate-300">
                <div><span className="text-slate-500">Campaign ID:</span> cmp_8402910a</div>
                <div><span className="text-slate-500">Merchant ID:</span> m_991823901</div>
                <div><span className="text-slate-500">Action Type:</span> PAYMENT_RETRY</div>
                <div><span className="text-slate-500">Evaluated At:</span> 2026-09-04T19:00:00Z</div>
                <div><span className="text-slate-500">Policy Checksum:</span> sha256_e91a0b38c2</div>
              </div>

              <div className="p-4 rounded-xl bg-emerald-950/40 border border-emerald-800/60 text-emerald-300 text-xs font-mono">
                System confirmed zero policy violations. Handing off intent to Razorpay payment provider worker.
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* SECTION 6: RECOVERY TIMELINE */}
      <section className="py-20 px-6 max-w-7xl mx-auto border-t border-slate-800/80">
        <div className="text-center max-w-2xl mx-auto mb-16">
          <span className="text-xs font-mono text-blue-400 font-bold uppercase">Lifecycle Transparency</span>
          <h2 className="text-3xl font-extrabold text-white mt-1">Payment Recovery Lifecycle Timeline</h2>
        </div>

        <div className="max-w-3xl mx-auto space-y-8 relative before:absolute before:inset-0 before:left-6 before:w-0.5 before:bg-slate-800">
          <div className="relative flex items-start space-x-6">
            <div className="w-12 h-12 rounded-2xl bg-rose-950 text-rose-400 border border-rose-800 flex items-center justify-center font-mono font-bold text-xs shrink-0 z-10">
              09:42
            </div>
            <div className="p-4 rounded-xl bg-slate-900 border border-slate-800 flex-1 space-y-1">
              <div className="font-bold text-white text-sm">Payment Failure Detected</div>
              <div className="text-xs text-slate-400 font-mono">Transaction pay_N9k2x1Lp89 failed for ₹4,999 (Razorpay webhook)</div>
            </div>
          </div>

          <div className="relative flex items-start space-x-6">
            <div className="w-12 h-12 rounded-2xl bg-blue-950 text-blue-400 border border-blue-800 flex items-center justify-center font-mono font-bold text-xs shrink-0 z-10">
              09:43
            </div>
            <div className="p-4 rounded-xl bg-slate-900 border border-slate-800 flex-1 space-y-1">
              <div className="font-bold text-white text-sm">Failure Class Diagnosis</div>
              <div className="text-xs text-slate-400 font-mono">AI Classifier tagged failure as INSUFFICIENT_FUNDS (96% confidence)</div>
            </div>
          </div>

          <div className="relative flex items-start space-x-6">
            <div className="w-12 h-12 rounded-2xl bg-indigo-950 text-indigo-400 border border-indigo-800 flex items-center justify-center font-mono font-bold text-xs shrink-0 z-10">
              09:45
            </div>
            <div className="p-4 rounded-xl bg-slate-900 border border-slate-800 flex-1 space-y-1">
              <div className="font-bold text-white text-sm">Compliance Approval & Action Intent</div>
              <div className="text-xs text-slate-400 font-mono">Safety gate passed. ActionIntent #act_9918 scheduled with Smart Retry</div>
            </div>
          </div>

          <div className="relative flex items-start space-x-6">
            <div className="w-12 h-12 rounded-2xl bg-emerald-950 text-emerald-400 border border-emerald-800 flex items-center justify-center font-mono font-bold text-xs shrink-0 z-10">
              14:15
            </div>
            <div className="p-4 rounded-xl bg-slate-900 border border-emerald-800/80 flex-1 space-y-1">
              <div className="font-bold text-emerald-400 text-sm">Payment Successfully Recovered</div>
              <div className="text-xs text-slate-300 font-mono">Automated retry settled. ₹4,999 recovered into merchant account.</div>
            </div>
          </div>
        </div>
      </section>

      {/* SECTION 7: AUDITABILITY */}
      <section id="audit" className="py-20 px-6 max-w-7xl mx-auto border-t border-slate-800/80">
        <div className="flex flex-col md:flex-row md:items-end justify-between mb-8 gap-4">
          <div>
            <span className="text-xs font-mono text-blue-400 font-bold uppercase tracking-wider">Immutable Ledger</span>
            <h2 className="text-3xl font-extrabold text-white mt-1">Auditability & Verification</h2>
          </div>
          <Link href="/audit" className="text-xs font-mono text-blue-400 hover:underline">
            View Full Audit Logs &rarr;
          </Link>
        </div>

        <div className="bg-slate-900 rounded-2xl border border-slate-800 overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm font-mono text-xs">
              <thead className="bg-slate-950 border-b border-slate-800 uppercase text-slate-400">
                <tr>
                  <th className="px-6 py-4">Timestamp</th>
                  <th className="px-6 py-4">Actor</th>
                  <th className="px-6 py-4">Action Event</th>
                  <th className="px-6 py-4">Entity</th>
                  <th className="px-6 py-4 text-right">Result</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800">
                <tr className="hover:bg-slate-800/40">
                  <td className="px-6 py-4 text-slate-400">12:42:18</td>
                  <td className="px-6 py-4 font-bold text-white">Recovery Engine</td>
                  <td className="px-6 py-4 text-blue-400 font-bold">ACTION_CREATED</td>
                  <td className="px-6 py-4 text-slate-300">Campaign #1842</td>
                  <td className="px-6 py-4 text-right text-emerald-400 font-bold">SUCCESS</td>
                </tr>

                <tr className="hover:bg-slate-800/40">
                  <td className="px-6 py-4 text-slate-400">12:41:03</td>
                  <td className="px-6 py-4 font-bold text-white">System Safety Gate</td>
                  <td className="px-6 py-4 text-blue-400 font-bold">COMPLIANCE_CHECK</td>
                  <td className="px-6 py-4 text-slate-300">Campaign #1842</td>
                  <td className="px-6 py-4 text-right text-emerald-400 font-bold">APPROVED</td>
                </tr>

                <tr className="hover:bg-slate-800/40">
                  <td className="px-6 py-4 text-slate-400">12:40:52</td>
                  <td className="px-6 py-4 font-bold text-white">AI Classifier</td>
                  <td className="px-6 py-4 text-blue-400 font-bold">FAILURE_CLASSIFIED</td>
                  <td className="px-6 py-4 text-slate-300">Payment #9821</td>
                  <td className="px-6 py-4 text-right text-emerald-400 font-bold">SUCCESS</td>
                </tr>

                <tr className="hover:bg-slate-800/40">
                  <td className="px-6 py-4 text-slate-400">12:38:11</td>
                  <td className="px-6 py-4 font-bold text-white">Redis Stream</td>
                  <td className="px-6 py-4 text-blue-400 font-bold">EVENT_INGESTED</td>
                  <td className="px-6 py-4 text-slate-300">Webhook #7712</td>
                  <td className="px-6 py-4 text-right text-emerald-400 font-bold">PROCESSED</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>

      {/* SECTION 8: MERCHANT SETTINGS PREVIEW */}
      <section className="py-20 px-6 max-w-7xl mx-auto border-t border-slate-800/80">
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-12 items-center">
          <div className="lg:col-span-6 space-y-6">
            <span className="text-xs font-mono text-blue-400 font-bold uppercase">Granular Controls</span>
            <h2 className="text-3xl font-extrabold text-white">Configurable Merchant Recovery Rules</h2>
            <p className="text-slate-300 text-base leading-relaxed">
              Tailor retry frequency, escalation limits, and notification windows to fit your merchant business policies.
            </p>
            <Link
              href="/settings"
              className="inline-flex items-center space-x-2 text-sm font-bold text-blue-400 hover:text-blue-300 transition-colors"
            >
              <span>Manage Merchant Settings</span>
              <span>&rarr;</span>
            </Link>
          </div>

          <div className="lg:col-span-6">
            <div className="p-6 rounded-2xl bg-slate-900 border border-slate-800 space-y-4 font-mono text-xs">
              <div className="flex justify-between items-center pb-3 border-b border-slate-800 text-slate-300">
                <span>Maximum Attempts</span>
                <span className="font-bold text-white px-2 py-0.5 rounded bg-slate-800">3 Attempts</span>
              </div>
              <div className="flex justify-between items-center pb-3 border-b border-slate-800 text-slate-300">
                <span>Contact Window</span>
                <span className="font-bold text-white px-2 py-0.5 rounded bg-slate-800">24 Hours</span>
              </div>
              <div className="flex justify-between items-center pb-3 border-b border-slate-800 text-slate-300">
                <span>Retry Strategy</span>
                <span className="font-bold text-blue-400 px-2 py-0.5 rounded bg-blue-950 border border-blue-800">SMART_RETRY (AI)</span>
              </div>
              <div className="flex justify-between items-center text-slate-300">
                <span>Escalation Threshold</span>
                <span className="font-bold text-white px-2 py-0.5 rounded bg-slate-800">2 Failures</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* FINAL CTA SECTION */}
      <section className="py-24 px-6 max-w-5xl mx-auto text-center border-t border-slate-800/80">
        <div className="p-12 rounded-3xl bg-gradient-to-b from-slate-900 to-slate-950 border border-slate-800 space-y-6">
          <h2 className="text-4xl sm:text-5xl font-black text-white tracking-tight">
            Recover more payments. Lose fewer customers.
          </h2>
          <p className="text-slate-300 text-base max-w-xl mx-auto">
            Deploy automated, compliant, and policy-bounded payment recovery operations across your Razorpay subscriptions and recurring revenue.
          </p>
          <div className="pt-4 flex justify-center">
            <Link
              href="/login"
              className="px-8 py-4 rounded-xl bg-blue-600 hover:bg-blue-500 text-white font-bold text-lg shadow-xl shadow-blue-600/30 transition-all flex items-center space-x-3"
            >
              <span>Open Mend Console</span>
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M14 5l7 7m0 0l-7 7m7-7H3" />
              </svg>
            </Link>
          </div>
        </div>
      </section>

      {/* FOOTER */}
      <footer className="border-t border-slate-800/80 py-8 px-6 text-center text-xs text-slate-500 font-mono flex flex-col sm:flex-row justify-between max-w-7xl mx-auto">
        <div>Mend Payment Recovery Platform &bull; Enterprise SaaS Operations Console</div>
        <div className="mt-2 sm:mt-0">PostgreSQL &bull; Redis Stream &bull; Razorpay API Integration</div>
      </footer>
    </div>
  );
}
