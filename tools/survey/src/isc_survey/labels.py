"""Explicit FR/EN label -> common code tables for the user survey (Google Forms exports).

Every answer label seen in either export must appear here. An unknown label is an ERROR
(`UnknownLabel`), never silently dropped: a renamed option in the form must fail loudly.
Q6 is deliberately NOT harmonised across languages: the FR form asks for the maximum balance
"ever", the EN form the maximum balance "per month" — two different questions.
"""

# Column index in the export (0 = Timestamp) -> question id.
QUESTIONS = {i: f"Q{i}" for i in range(1, 24)}
MULTI = {"Q5", "Q23"}  # multiple answers, separated by ";"
NUMERIC = {"Q14"}  # integer scale 1-10

# Expected second header per language (sanity check of the export).
SECOND_HEADER = {"fr": "Activité principale", "en": "Main occupation"}

LABELS: dict[str, dict[str, dict[str, str]]] = {
    "Q1": {
        "fr": {
            "Étudiant": "student",
            "Salarié": "employee",
            "Sans emploi": "unemployed",
            "Chef d'entreprise / Travailleur indépendant": "self_employed",
        },
        "en": {
            "Student": "student",
            "Employee": "employee",
            "Unemployed": "unemployed",
            "Business owner / self-employed": "self_employed",
        },
    },
    "Q2": {
        "fr": {"Oui , Orange Money": "orange", "Oui , MTN MoMo": "mtn", "Les Deux": "both"},
        "en": {"Yes, Orange Money": "orange", "Yes, MTN MoMo": "mtn", "Both": "both"},
    },
    "Q3": {
        "fr": {
            "App (My MTN , Orange Money)": "app",
            "Code USSD (*126#, #150#)": "ussd",
            "Both": "both",
        },
        "en": {
            "App (MyMTN, Orange Money)": "app",
            "USSD code (_126#, #150#)": "ussd",
            "Both": "both",
        },
    },
    "Q4": {
        "fr": {
            "Tous les jours": "daily",
            "Plusieurs fois par semaine": "several_weekly",
            "Quelques fois par mois": "few_monthly",
        },
        "en": {
            "Every day": "daily",
            "Several times a week": "several_weekly",
            "A few times a month": "few_monthly",
            "Rarely": "rarely",
        },
    },
    "Q5": {
        "fr": {
            "Envoyer / recevoir de l'argent (famille, proches)": "send_receive",
            "Payer des factures (électricité, eau, internet…)": "bills",
            "Payer des achats chez des marchands": "merchants",
            "Recevoir un salaire ou des revenus d'activité": "salary",
            "Épargner de l'argent": "savings",
        },
        "en": {
            "Sending / receiving money (family, loved ones)": "send_receive",
            "Paying bills (electricity, water, internet, etc.)": "bills",
            "Paying for purchases at merchants": "merchants",
            "Receiving a salary or income from work": "salary",
            "Saving money": "savings",
        },
    },
    # Q6: same bands, but NOT the same question (ever vs per month) -> never merged.
    "Q6": {
        "fr": {
            "Moins de 10 000 FCFA": "lt_10k",
            "10 000 – 50 000 FCFA": "10k_50k",
            "50 000 – 100 000 FCFA": "50k_100k",
            "100 000 – 250 000 FCFA": "100k_250k",
            "250 000 – 500 000 FCFA": "250k_500k",
            "Plus de 500 000 FCFA": "gt_500k",
        },
        "en": {
            "Less than 10,000 FCFA": "lt_10k",
            "10,000 – 50,000 FCFA": "10k_50k",
            "50,000 – 100,000 FCFA": "50k_100k",
            "100,000 – 250,000f FCFA": "100k_250k",
            "250,000 – 500,000 FCFA": "250k_500k",
            "More than 500,000 FCFA": "gt_500k",
        },
    },
    "Q7": {
        "fr": {
            "Non, jamais entendu parler": "never",
            "J'en ai vaguement entendu parler": "vaguely",
            "Oui, je connais bien": "well",
        },
        "en": {
            "No , never heard": "never",
            "I have heard vaguely": "vaguely",
            "Yes , I know well": "well",
        },
    },
    "Q8": {"fr": {"Yes": "yes", "No": "no"}, "en": {"Yes": "yes", "No": "no"}},
    "Q9": {
        "fr": {"No": "no", "Oui, une fois": "once", "Oui, souvent.": "often"},
        "en": {"No": "no", "Yes ,once": "once", "Yes ,often": "often"},
    },
    "Q10": {"fr": {"Oui": "yes", "Non": "no"}, "en": {"Yes": "yes", "No": "no"}},
    "Q11": {
        "fr": {
            "Moi-même": "self",
            "J'en ai entendu parler par d'autres": "heard_others",
            "Non, jamais": "never",
        },
        "en": {
            "Myself": "self",
            "Someone close to me": "close",  # option absent from the FR form
            "I have heard about it from others": "heard_others",
            "No, never": "never",
        },
    },
    "Q12": {
        "fr": {
            "Non": "no",
            "Je ne sais pas": "dont_know",
            "Oui, en totalité": "full",
            "Partiellement": "partial",
        },
        "en": {
            "No": "no",
            "I do not know": "dont_know",
            "Yes , in full": "full",
            "Partially": "partial",
        },
    },
    "Q13": {
        "fr": {
            "Peu important – J'y ai peu d'argent": "low",
            "Important – Je perdrais beaucoup": "serious",
            "C’est catastrophique : il s’agit de mon entreprise/ loyer /frais de scolarité "
            "de mes enfants.": "catastrophic",
        },
        "en": {
            "Not serious – I have little money inside": "low",
            "Serious – I would lose a lot": "serious",
            "Catastrophic – it’s my business/rent /children’s school fees": "catastrophic",
        },
    },
    "Q15": {"fr": {"Oui": "yes", "Non": "no"}, "en": {"Yes": "yes", "No": "no"}},
    "Q16": {
        "fr": {"Très utile": "very", "Modérément utile": "moderate", "Inutile": "useless"},
        "en": {"Very useful": "very", "Moderately useful": "moderate", "Useless": "useless"},
    },
    "Q17": {
        "fr": {
            "Oui, à chaque fois si nécessaire.": "yes_always",
            "Oui, à condition que cela reste rare.": "yes_if_rare",
            "Non": "no",
        },
        "en": {
            "Yes , every time if necessary": "yes_always",
            "Yes , provided it remains rare": "yes_if_rare",
            "No": "no",
        },
    },
    "Q18": {
        "fr": {"Oui": "yes", "Ça dépend:": "depends", "Non": "no"},
        "en": {"Yes": "yes", "It depends:": "depends", "No": "no"},
    },
    "Q19": {
        "fr": {
            "Se rendre en agence muni d'une pièce d'identité": "branch",
            "Une adresse e-mail de récupération enregistrée au préalable": "email",
            "Une personne de confiance désignée à l'avance qui confirme votre identité": (
                "trusted_person"
            ),
        },
        "en": {
            "Visiting a branch with an identity document": "branch",
            "A recovery email address registered in advance": "email",
            "A trusted person designated in advance who confirms your identity": ("trusted_person"),
        },
    },
    "Q20": {
        "fr": {"Oui": "yes", "Aucun avis": "no_opinion", "Non": "no"},
        "en": {"Yes": "yes", "No opinion": "no_opinion", "No": "no"},
    },
    "Q21": {
        "fr": {
            "Oui, immédiatement": "yes_now",
            "Oui, seulement si c'est gratuit": "yes_if_free",
            "Non, c'est trop compliqué": "no",
        },
        "en": {
            "Yes ,immediatly": "yes_now",
            "Yes, only if it is free": "yes_if_free",
            "No it is too complicated": "no",
        },
    },
    "Q22": {
        "fr": {
            "Oui, je le noterais et le conserverais soigneusement": "yes_keep",
            "Oui, mais j'aurais peur de le perdre": "yes_afraid",
            "Je préférerais une autre méthode": "prefer_other",  # FR only
        },
        "en": {
            "Yes, I would write it down and keep it safe": "yes_keep",
            "Yes, but I would be afraid of losing it": "yes_afraid",
            "No, it's too much of a hassle": "no_hassle",  # EN only
        },
    },
    "Q23": {
        "fr": {
            "Crainte d'être bloqué injustement": "fear_blocked",
            "Trop compliqué à utiliser": "complicated",
            "Confidentialité (mes habitudes, ma localisation)": "privacy",
            "Rien, je l'adopterais sans hésiter": "nothing",
        },
        "en": {
            "Fear of being unfairly blocked": "fear_blocked",
            "Too complicated to use": "complicated",
            "Privacy (my habits, my location)": "privacy",
            "Nothing, I would adopt it without hesitation": "nothing",
            "Cost": "cost",  # option absent from the FR form
        },
    },
}
