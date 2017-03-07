DataTags: consists of assertions, entitlements.

assertions [Relevant properties of the claiment] : consists of insuranceStatus, coverCause, eligibility,
                          qualificationPeriodCompleted, gender, ageGroup,
                          employmentStatus, dependents.

insuranceStatus: one of NotCovered [Not insured under Israeli law],
                        Covered [Insured],
                        SpecialCovered [TBD].

gender: one of Male, Female.
ageGroup: one of Minor, WorkForce, Pension.

coverCause [Why is the applicant eligible]: some of MilitaryService, NationalService,
                                                    Residence, Employment.

eligibility: one of No, Yes.
qualificationPeriodCompleted: one of No, Yes.
employmentStatus: one of Employed [Working, NIS paid for],
                         NotUnemployed [Not working, but not eligible for unemployment benefits],
                         Unemployed [Eligible for unemployment benefits].

entitlements [What the claiment is entitled to receive]: consists of capping, numOfDays.

capping [Based on article 167 of the National Insurance law]: one of
  AverageDailyPay[Average monthy pay divided by 25 (see article 167)],
  TwoThirdsAverageDailyPay[Two thirds of average monthy pay divided by 25 (see article 167)].

dependents [How many people depend on claiment as a main source for monetary support]: one of LessThanThree, ThreeOrMore.

numOfDays: one of d50, d67, d100, d138, d175.
